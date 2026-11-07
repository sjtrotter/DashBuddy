package cloud.trotter.log.gig.ui.main

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.R as DesignR
import cloud.trotter.log.gig.core.designsystem.component.AppPlaceholder
import cloud.trotter.log.gig.core.designsystem.component.rememberPlaceholderAlpha
import cloud.trotter.log.gig.core.designsystem.motion.LocalReducedMotion
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.ui.ScreenshotData
import cloud.trotter.log.gig.ui.components.RECORDS_UPDATING_LINE_DELAY_MS
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsContent
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsTab
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsUiState
import cloud.trotter.log.gig.ui.main.playbook.PlaybookContent
import cloud.trotter.log.gig.ui.main.playbook.PlaybookUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The records states the hub and the Playbook share: REBUILDING holds every figure back, UPDATING is
 * one line that follows the status, a failed read offers Retry, and the placeholder reads "Loading"
 * and holds still under reduced motion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordsStatusScreensTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun show(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme { CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock, content = content) }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    /** A state write from the test thread, published to the composition, then [thenMs] of frames. */
    private fun change(thenMs: Long = 2_000, write: () -> Unit) {
        write()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(thenMs)
    }

    @Composable
    private fun Hub(state: AnalyticsUiState, onRetry: () -> Unit = {}) = AnalyticsContent(
        uiState = state,
        offersFeed = { ScreenshotData.offersFeed },
        onOpenSettings = {}, onExportCsv = {}, onOpenSession = {}, onStep = {}, onOpenPicker = {},
        onSelectGranularity = {}, onSelectTab = {}, onOpenNoSession = {},
        onSelectFilter = {}, onShowAllOffers = {}, onToggleShowMore = {}, onRetry = onRetry,
    )

    @Composable
    private fun Playbook(state: PlaybookUiState, onRetry: () -> Unit = {}) = PlaybookContent(
        uiState = state, onOpenSettings = {}, onOpenPlan = {}, onToggleShowMore = {}, onRetry = onRetry,
    )

    private fun assertAbsent(text: String) =
        compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)

    @Test fun `a rebuilding hub shows no zero total and no empty-period line`() {
        // Empty economics: the figures this state would otherwise draw are "$0.00" and "Nothing recorded".
        var state by mutableStateOf(AnalyticsUiState(records = RecordsStatus.UP_TO_DATE))
        show { Hub(state) }
        compose.onNodeWithText(string(R.string.analytics_hero_no_data), substring = true).assertExists()

        change { state = state.copy(records = RecordsStatus.REBUILDING) }
        compose.onNodeWithText(string(R.string.records_rebuilding_title)).assertExists()
        compose.onNodeWithText(string(R.string.records_rebuilding_body)).assertExists()
        assertAbsent("$0.00")
        assertAbsent(string(R.string.analytics_hero_no_data))
    }

    @Test fun `a rebuilding Playbook shows no empty plan and no empty store list`() {
        var state by mutableStateOf(PlaybookUiState(loading = false))
        show { Playbook(state) }
        compose.onNodeWithText(string(R.string.playbook_plan_none_title)).assertExists()

        change { state = state.copy(records = RecordsStatus.REBUILDING) }
        compose.onNodeWithText(string(R.string.records_rebuilding_title)).assertExists()
        assertAbsent("$0.00")
        assertAbsent(string(R.string.playbook_plan_none_title))
        assertAbsent(string(R.string.patterns_tab_no_stores))
    }

    @Test fun `a brief lag shows no updating line and a sustained one does, on both screens`() {
        var hub by mutableStateOf(ScreenshotData.analytics(AnalyticsTab.Money, showMore = false))
        var playbook by mutableStateOf(ScreenshotData.playbook(showMore = false))
        var onHub by mutableStateOf(true)
        show { if (onHub) Hub(hub) else Playbook(playbook) }
        val line = string(R.string.records_updating)

        fun status(records: RecordsStatus, thenMs: Long) = change(thenMs) {
            if (onHub) hub = hub.copy(records = records) else playbook = playbook.copy(records = records)
        }

        for (screen in listOf(true, false)) {
            change { onHub = screen }
            // A blip: an ordinary fold catches up well inside the delay, so the line never shows.
            status(RecordsStatus.UPDATING, thenMs = RECORDS_UPDATING_LINE_DELAY_MS / 4)
            compose.onNodeWithText(line).assertDoesNotExist()
            status(RecordsStatus.UP_TO_DATE, thenMs = RECORDS_UPDATING_LINE_DELAY_MS * 2)
            compose.onNodeWithText(line).assertDoesNotExist()

            // A sustained lag: hidden just short of the delay, shown past it, gone once caught up.
            status(RecordsStatus.UPDATING, thenMs = RECORDS_UPDATING_LINE_DELAY_MS - 500)
            compose.onNodeWithText(line).assertDoesNotExist()
            change(1_000) {}
            compose.onNodeWithText(line).assertExists()
            status(RecordsStatus.UP_TO_DATE, thenMs = 100)
            compose.onNodeWithText(line).assertDoesNotExist()
        }
    }

    @Test fun `a failed read shows Couldn't load and Retry asks again`() {
        var retries = 0
        var onHub by mutableStateOf(true)
        show {
            if (onHub) Hub(AnalyticsUiState(failed = true), onRetry = { retries++ })
            else Playbook(PlaybookUiState(loading = false, failed = true), onRetry = { retries++ })
        }
        val retry = string(DesignR.string.app_retry)

        compose.onNodeWithText(string(DesignR.string.app_could_not_load, string(R.string.analytics_could_not_load_what)))
            .assertExists()
        assertAbsent("$0.00")
        compose.onNodeWithText(retry).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, retries)

        change { onHub = false }
        compose.onNodeWithText(string(DesignR.string.app_could_not_load, string(R.string.playbook_could_not_load_what)))
            .assertExists()
        compose.onNodeWithText(retry).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(2, retries)
    }

    @Test fun `the screens' loading bodies read Loading`() {
        var onHub by mutableStateOf(true)
        show {
            if (onHub) Hub(AnalyticsUiState(loaded = false)) else Playbook(PlaybookUiState())
        }
        compose.onNodeWithContentDescription(string(R.string.common_loading)).assertExists()
        assertAbsent("$0.00")
        change { onHub = false }
        compose.onNodeWithContentDescription(string(R.string.common_loading)).assertExists()
        assertAbsent(string(R.string.playbook_plan_none_title))
    }

    private fun pulse(reduced: Boolean): List<Float> {
        val alphas = mutableListOf<Float>()
        show {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                alphas += rememberPlaceholderAlpha().value
                AppPlaceholder(Modifier.size(40.dp))
            }
        }
        repeat(8) { compose.mainClock.advanceTimeBy(250) }
        compose.onNodeWithContentDescription(string(DesignR.string.app_placeholder_loading)).assertExists()
        return alphas
    }

    @Test fun `the placeholder pulses and reads Loading`() {
        val alphas = pulse(reduced = false)
        assertTrue("the pulse moves: $alphas", alphas.any { it < 0.99f })
    }

    @Test fun `the placeholder holds still under reduced motion`() {
        val alphas = pulse(reduced = true)
        assertEquals("one value, never animated: $alphas", setOf(1f), alphas.toSet())
    }
}
