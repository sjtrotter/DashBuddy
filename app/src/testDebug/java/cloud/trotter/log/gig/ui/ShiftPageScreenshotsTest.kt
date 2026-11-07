package cloud.trotter.log.gig.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageActions
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageContent
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageFixtures
import cloud.trotter.log.gig.ui.main.analytics.SessionDetailUiState
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
 * The shift page for PR CI's screenshots (#1463's procedure, [renderShot]): a stack and an add-on, the 47-offer
 * shape collapsed and with one run open, and a job's sheet, at 100 % and 200 % text, light and dark.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class ShiftPageScreenshotsTest(private val theme: String) {
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

    private val stack get() = ShiftPageFixtures.stackAndAddOn()
    private val heavy get() = ShiftPageFixtures.heavyShift()

    /** At 200 % text the timeline starts below the fold; the 200 % shots assert the page's first figure instead. */
    private val earned get() = context.getString(R.string.shift_page_earned_label)

    @Test fun stackAndAddOn() = page("shift/stack-addon", stack, expect = "Five Guys & Chipotle")

    @Test @Config(fontScale = 2f)
    fun stackAndAddOnFont200() = page("shift/stack-addon-font-200", stack, expect = earned)

    @Test fun heavyCollapsed() = page("shift/heavy-collapsed", heavy, expect = "12 declined, 3 expired")

    @Test @Config(fontScale = 2f)
    fun heavyCollapsedFont200() = page("shift/heavy-collapsed-font-200", heavy, expect = earned)

    /** The second run opened by a link to one of its offers. */
    @Test fun heavyRunOpen() = page("shift/heavy-run-open", heavy, row = "offer:1020", expect = "Declined ·")

    @Test @Config(fontScale = 2f)
    fun heavyRunOpenFont200() = page("shift/heavy-run-open-font-200", heavy, row = "offer:1020", expect = "Declined ·")

    @Test fun sheet() = page("shift/sheet", stack, row = ShiftPageFixtures.STACK_KEY, window = true,
        expect = context.getString(R.string.shift_page_fix_delivery))

    @Test @Config(fontScale = 2f)
    fun sheetFont200() = page("shift/sheet-font-200", stack, row = ShiftPageFixtures.STACK_KEY, window = true,
        expect = context.getString(R.string.shift_page_fix_delivery))

    private fun page(path: String, state: SessionDetailUiState, expect: String, row: String? = null, window: Boolean = false) =
        compose.renderShot(theme, path, window, assertion = {
            compose.onAllNodesWithText(expect, substring = true)[0].assertExists()
        }) { Page(state, row) }

    @Composable
    private fun Page(state: SessionDetailUiState, row: String?) =
        ShiftPageContent(state, row, ShiftPageActions(onBack = {}))

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf<Any>("light"), arrayOf<Any>("dark"))
    }
}
