package cloud.trotter.log.gig.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.ui.main.dashboard.DashboardBody
import cloud.trotter.log.gig.ui.main.dashboard.DashboardUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #1524: Home never shows the first-run card before the setup flag is read; it shows the loading body. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DashboardLoadingTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val firstRunTitle get() = context.getString(R.string.dashboard_screen_first_run_title)
    private val homeContent get() = context.getString(R.string.dashboard_ratings_action)
    private val loading get() = context.getString(R.string.common_loading)

    private fun show(uiState: DashboardUiState) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock) {
                    DashboardBody(
                        uiState = uiState, hasPermissions = true, screenReadingOff = false,
                        onContinueGate = {}, onOpenWizard = {}, onCompleteSetup = {}, onShowBubble = {},
                        onOpenRecap = {}, onOpenPlan = {}, onOpenRatings = {}, onOpenSettings = {},
                    )
                }
            }
        }
    }

    @Test fun `an unread setup flag shows neither the first-run card nor Home, then the loading indicator`() {
        show(ScreenshotData.dashboard().copy(isFirstRun = null))
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText(firstRunTitle).assertDoesNotExist()
        compose.onNodeWithText(homeContent).assertDoesNotExist()
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()

        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription(loading).assertExists()
        compose.onNodeWithText(firstRunTitle).assertDoesNotExist()
    }

    @Test fun `a read setup flag that is not first run shows Home without the first-run card`() {
        show(ScreenshotData.dashboard(isFirstRun = false))
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(firstRunTitle).assertDoesNotExist()
        compose.onNodeWithText(homeContent).assertExists()
        compose.onNodeWithContentDescription(loading).assertDoesNotExist()
    }
}
