package cloud.trotter.log.gig.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import cloud.trotter.log.gig.ui.main.MainScaffold
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsContent
import cloud.trotter.log.gig.ui.main.analytics.AnalyticsUiState
import cloud.trotter.log.gig.ui.main.dashboard.DashboardBody
import cloud.trotter.log.gig.ui.main.dashboard.DashboardUiState
import cloud.trotter.log.gig.ui.main.navigation.TopLevel
import cloud.trotter.log.gig.ui.main.playbook.PlaybookContent
import cloud.trotter.log.gig.ui.main.playbook.PlaybookUiState

// The three tabs' bodies in the app shell's frame, so the bottom bar shows under them (#1447).

@Composable
internal fun HomeBody(state: DashboardUiState, hasPermissions: Boolean?, screenReadingOff: Boolean = false) =
    MainScaffold(selectedTab = TopLevel.HOME, onSelectTab = {}) { modifier ->
        Box(modifier) {
            DashboardBody(
                uiState = state, hasPermissions = hasPermissions, screenReadingOff = screenReadingOff,
                onContinueGate = {}, onOpenWizard = {}, onCompleteSetup = {}, onShowBubble = {},
                onOpenRecap = {}, onOpenPlan = {}, onOpenRatings = {}, onOpenSettings = {},
            )
        }
    }

@Composable
internal fun RecapBody(state: AnalyticsUiState) =
    MainScaffold(selectedTab = TopLevel.ANALYTICS, onSelectTab = {}) { modifier ->
        Box(modifier) {
            AnalyticsContent(
                uiState = state, onOpenSettings = {}, onStep = {}, onSelectMeasure = {}, onOpenDay = {},
                onOpenShift = { _, _ -> }, onOpenShifts = {}, onOpenNoSession = {}, onRetry = {},
            )
        }
    }

@Composable
internal fun PlaybookBody(state: PlaybookUiState) =
    MainScaffold(selectedTab = TopLevel.PLAYBOOK, onSelectTab = {}) { modifier ->
        Box(modifier) {
            PlaybookContent(uiState = state, onOpenSettings = {}, onOpenPlan = {}, onToggleShowMore = {}, onRetry = {})
        }
    }
