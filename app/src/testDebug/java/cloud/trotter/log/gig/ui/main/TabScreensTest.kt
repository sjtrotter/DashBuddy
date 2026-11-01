package cloud.trotter.log.gig.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.config.OfferAutomationConfig
import cloud.trotter.log.gig.feature.settings.StrategySettingsContent
import cloud.trotter.log.gig.ui.ScreenshotData
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsContent
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsTab
import cloud.trotter.log.gig.ui.main.dashboard.DashboardBody
import cloud.trotter.log.gig.ui.main.playbook.PlaybookContent
import cloud.trotter.log.gig.ui.main.settings.SettingsHomeContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #1447 — the tab roots carry no back button and a Settings gear; Home lost its entry tiles for a
 * Ratings row; Settings home and Offer rules, below a tab, have a back button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabScreensTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val calls = mutableListOf<String>()
    private val back get() = context.getString(R.string.common_content_desc_back)
    private val settings get() = context.getString(R.string.settings_home_title)

    /** Home ticks (TodayHeader), so its tests own the clock: frozen frames, a fixed `now`. */
    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme { CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock, content = content) }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    /**
     * Click through the node's OnClick action. Home ticks, so its clock is paused; under a paused
     * clock a scroll (performScrollTo animates) never finishes and waitForIdle spins forever, so a
     * row below the fold is clicked by its action rather than scrolled to and tapped.
     */
    private fun click(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(2_000)
    }

    private enum class HomeState { GATE, FIRST_RUN, READY }

    @Test fun `Home's gear opens Settings in every state and the Ratings row opens Ratings`() {
        var state by mutableStateOf(HomeState.GATE)
        show {
            DashboardBody(
                uiState = ScreenshotData.dashboard(isFirstRun = state == HomeState.FIRST_RUN),
                hasPermissions = state != HomeState.GATE,
                screenReadingOff = false,
                onContinueGate = {}, onOpenWizard = { calls += "wizard" }, onCompleteSetup = {}, onShowBubble = {},
                onOpenRecap = { calls += "recap" }, onOpenPlan = { calls += "plan" },
                onOpenRatings = { calls += "ratings" }, onOpenSettings = { calls += "settings" },
            )
        }
        for (each in HomeState.entries) {
            state = each
            compose.mainClock.advanceTimeBy(2_000)
            compose.onNodeWithContentDescription(back).assertDoesNotExist()
            click(compose.onNodeWithContentDescription(settings))
        }
        assertEquals(List(HomeState.entries.size) { "settings" }, calls)

        // Ready: no entry tiles; Ratings is one row.
        compose.onNodeWithText(context.getString(R.string.analytics_screen_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.playbook_screen_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.ratings_screen_title)).assertExists()
        click(compose.onNodeWithText(context.getString(R.string.dashboard_ratings_action)))
        assertEquals("ratings", calls.last())
    }

    @Test fun `Analytics has a gear and no back button`() {
        show {
            AnalyticsContent(
                uiState = ScreenshotData.analytics(AnalyticsTab.Money, showMore = false),
                offersFeed = { ScreenshotData.offersFeed },
                onOpenSettings = { calls += "settings" }, onExportCsv = {}, onOpenSession = {}, onStep = {},
                onOpenPicker = {}, onSelectGranularity = {}, onSelectTab = {}, onOpenNoSession = {},
                onOpenOrphanOffers = {}, onSelectFilter = {}, onShowAllOffers = {}, onToggleShowMore = {},
            )
        }
        compose.onNodeWithContentDescription(back).assertDoesNotExist()
        click(compose.onNodeWithContentDescription(settings))
        assertEquals(listOf("settings"), calls)
    }

    @Test fun `Playbook has a gear and no back button`() {
        show {
            PlaybookContent(
                uiState = ScreenshotData.playbook(showMore = false),
                onOpenSettings = { calls += "settings" }, onOpenPlan = {}, onToggleShowMore = {},
            )
        }
        compose.onNodeWithContentDescription(back).assertDoesNotExist()
        click(compose.onNodeWithContentDescription(settings))
        assertEquals(listOf("settings"), calls)
    }

    @Test fun `Settings home's back button calls back`() {
        show {
            SettingsHomeContent(
                isDevUnlocked = false, garage = ScreenshotData.garage, userEconomy = ScreenshotData.userEconomy,
                onNavigate = {}, onBack = { calls += "back" },
            )
        }
        click(compose.onNodeWithContentDescription(back))
        assertEquals(listOf("back"), calls)
    }

    @Test fun `Offer rules' back button calls back`() {
        show {
            StrategySettingsContent(
                config = ScreenshotData.evaluationConfig, automation = OfferAutomationConfig(),
                onBack = { calls += "back" }, onReorder = {}, onToggleProtect = {}, onToggleShopping = {},
                onToggleQuickDeclines = {}, onUpdateRule = {},
            )
        }
        click(compose.onNodeWithContentDescription(back))
        assertEquals(listOf("back"), calls)
    }
}
