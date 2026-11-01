package cloud.trotter.log.gig.ui.main.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.domain.analytics.OrphanOfferGroup
import cloud.trotter.log.gig.domain.analytics.PeriodEconomics
import cloud.trotter.log.gig.core.designsystem.component.AppCard
import cloud.trotter.log.gig.feature.dashboard.components.DashboardRow
import cloud.trotter.log.gig.feature.dashboard.components.DashboardRowTitle
import cloud.trotter.log.gig.feature.dashboard.components.DashingStatusRow
import cloud.trotter.log.gig.feature.dashboard.components.StatusCard
import cloud.trotter.log.gig.feature.dashboard.components.TodayCard
import cloud.trotter.log.gig.feature.dashboard.components.TodayHeader
import cloud.trotter.log.gig.feature.dashboard.components.WeekCard
import cloud.trotter.log.gig.ui.components.HowNumbersWorkFooter
import cloud.trotter.log.gig.ui.main.analytics.ReviewItem
import cloud.trotter.log.gig.ui.main.analytics.ReviewList
import cloud.trotter.log.gig.ui.main.analytics.reviewTexts
import cloud.trotter.log.gig.ui.main.setup.consent.FrontDoorHost
import cloud.trotter.log.gig.ui.main.setup.permissions.PermissionType
import cloud.trotter.log.gig.ui.main.setup.permissions.PermissionsBottomSheet
import cloud.trotter.log.gig.ui.main.setup.permissions.PermissionsViewModel
import kotlinx.coroutines.launch

/**
 * Home — **"Today"** since #977, decluttered to **four blocks** by #1024 section D.
 *
 * Top to bottom: the "Home" title bar with the Settings gear (#1447) · header (date · live clock ·
 * status pill) · **Today** (kept, the four supporting figures, a hairline, then today's plan strip) ·
 * **This week** (net + delta + sparkline + `Recap →`, the weekly-plan pointer, the review items —
 * three hairline rows of one card) · the Ratings row · the shared **"How these numbers work"**
 * footer, the screen's single disclosure affordance (#1024 rule 2). Analytics and Playbook are the
 * bottom bar's other two tabs (#1447), so Home no longer carries entry tiles for them.
 *
 * Read-side only — nothing here writes to the state machine, and #1024 D added no read: the merge is
 * subtraction and re-composition of surfaces that already shipped.
 *
 * The host stays in `:app` (nav start destination, the `Screen` route table, the `:app`-owned
 * permission/consent sheets); the presentational blocks live in `:feature:dashboard`. The one
 * deliberate exception is the review rows, which are driven by the analytics hub's own
 * [reviewItems] — the flag rules and their copy have one owner in `:app`, and duplicating them into a
 * feature module to satisfy placement would be exactly the divergence Principle 5 exists to prevent.
 * They are handed to [WeekCard] as a slot, which is what lets the row sit inside the week card
 * without the feature module ever seeing an `:app` type.
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onOpenAnalytics: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenRatings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWizard: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // `Recap →` and every review-item action land on the same place: the Analytics hub, anchored on
    // the week Home was just describing, opened fresh (#1447). The selection write is ordered BEFORE
    // the navigation so the hub opens already on that window rather than flickering through its last one.
    // Remembered so it is a STABLE lambda: the review section keys its slot on it, and a fresh
    // instance every recomposition would defeat that memo.
    val openWeekRecap: () -> Unit = remember(scope, viewModel, onOpenAnalytics) {
        {
            scope.launch {
                viewModel.selectWeekRecapWindow()
                onOpenAnalytics()
            }
            Unit
        }
    }

    // The permission gate's ONE projection (#944, #1151 review TT4): the OS permissions AND the
    // event-receipt consent's Screen-events step, from PermissionsViewModel (the same nav-entry
    // instance the sheet uses). ON_RESUME re-polls the OS; the consent is reactive. Nothing is
    // decided until the consent is READ (`consentRead`) — then the gate opens whenever a step is
    // PENDING (outstanding and not put off, #1457), the Screen-events step always at its head.
    val permissionsViewModel: PermissionsViewModel = hiltViewModel()
    val permissions by permissionsViewModel.uiState.collectAsStateWithLifecycle()
    val hasPermissions: Boolean? = if (permissions.consentRead) permissions.allGranted else null
    val stepsPending = permissions.consentRead && permissions.pendingSteps.isNotEmpty()
    var showPermissionSheet by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionsViewModel.refresh() }
    LaunchedEffect(stepsPending) {
        if (stepsPending) showPermissionSheet = true
    }

    // ========================================================================
    // THE GATE: If a step is pending, force the Bottom Sheet to appear
    // ========================================================================
    if (showPermissionSheet) {
        PermissionsBottomSheet(
            onClosed = { showPermissionSheet = false },
            viewModel = permissionsViewModel,
        )
    }

    // The front door (#843 capability consent): once the permission chain — whose FIRST step is the
    // #1151 event-receipt consent (`ScreenEventsCard`) — is complete, the capability prompt may
    // show. Held back while the permission gate is up so no two sheets ever stack. #1457: a
    // deferred step keeps it held back too (it waits for every step to be granted) — intended.
    if (hasPermissions == true && !showPermissionSheet) {
        FrontDoorHost()
    }

    DashboardBody(
        uiState = uiState,
        hasPermissions = hasPermissions,
        screenReadingOff = PermissionType.Accessibility in permissions.missing,
        onContinueGate = {
            permissionsViewModel.reopen()
            showPermissionSheet = true
        },
        onOpenWizard = onOpenWizard,
        onCompleteSetup = { viewModel.completeSetup() },
        onShowBubble = { viewModel.showWelcomeBubble() },
        onOpenRecap = openWeekRecap,
        onOpenPlan = onOpenPlan,
        onOpenRatings = onOpenRatings,
        onOpenSettings = onOpenSettings,
    )
}

/**
 * Home's body: the gate, first-run and ready states (#1463 hoisted it out of [DashboardScreen] so a
 * screenshot test can render it without Hilt). Stateless: state down, intents up; the ViewModels,
 * the permission sheet and the front door stay in [DashboardScreen].
 *
 * The title bar (#1447) shows in every state, the loading one included, so Settings — and with it
 * Data & privacy's log export — is one tap away even while a permission step is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardBody(
    uiState: DashboardUiState,
    hasPermissions: Boolean?,
    screenReadingOff: Boolean,
    onContinueGate: () -> Unit,
    onOpenWizard: () -> Unit,
    onCompleteSetup: () -> Unit,
    onShowBubble: () -> Unit,
    onOpenRecap: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenRatings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_screen_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_home_title),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .fillMaxSize()
        ) {
            when {
                // CASE 0: Loading — prevents the flicker.
                hasPermissions == null -> {
                    // Empty state while calculating.
                }

                // CASE 1: Permissions Missing (The Gate). #1457: the button forgets every
                // deferral and reopens the sheet at its first pending step (the disclosure card,
                // never Android settings directly).
                hasPermissions == false -> {
                    PermissionGateCard(
                        screenReadingOff = screenReadingOff,
                        onContinue = onContinueGate,
                    )
                }

                // CASE 2: Permissions Granted, first run (The Guide)
                uiState.isFirstRun -> {
                    StatusCard(
                        title = stringResource(R.string.dashboard_screen_first_run_title),
                        subtitle = stringResource(R.string.dashboard_screen_first_run_subtitle),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenWizard
                    ) { Text(stringResource(R.string.dashboard_screen_personalize_strategy_button)) }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onCompleteSetup
                    ) { Text(stringResource(R.string.dashboard_screen_skip_for_now_button)) }
                }

                // CASE 3: Ready — the "Today" screen (brief §2, decluttered by #1024 D).
                else -> {
                    TodayHeader(
                        today = uiState.today,
                        statusText = stringResource(uiState.statusText),
                        dashing = uiState.isDashing,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (uiState.isDashing) {
                        DashingStatusRow(onTap = onShowBubble)
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // BLOCK 1 — today: what it kept, and what is still worth working (D1).
                    TodayCard(
                        economics = uiState.todayEconomics,
                        today = uiState.today,
                        heatmap = uiState.heatmap,
                        sessionStartedAt = uiState.sessionStartedAt,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // BLOCK 2 — the week: recap, plan pointer, review items (D4). The plan row
                    // renders only with a saved plan and the review row only with a raised flag —
                    // both are decided by the data, not by a placeholder.
                    WeekCard(
                        economics = uiState.weekEconomics,
                        previousEconomics = uiState.previousWeekEconomics,
                        dailyEarnings = uiState.weekDailyEarnings,
                        plan = uiState.weeklyPlan,
                        onOpenRecap = onOpenRecap,
                        onOpenPlan = onOpenPlan,
                        reviewContent = rememberReviewSlot(
                            economics = uiState.weekEconomics,
                            orphanOfferGroups = uiState.orphanOfferGroups,
                            onOpen = onOpenRecap,
                        ),
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // BLOCK 3 — Ratings, the one destination below Home (#1447: Analytics and
                    // Playbook are tabs, Settings is the title bar's gear).
                    RatingsRow(onOpenRatings = onOpenRatings)
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onShowBubble
                    ) { Text(stringResource(R.string.dashboard_screen_show_bubble_button)) }

                    // BLOCK 4 — the screen's one disclosure (#1024 rule 2).
                    Spacer(modifier = Modifier.height(20.dp))
                    // Home projects (the plan strip's best-stretch rate), so the footer carries the
                    // projection disclaimer #1024 D2 took off the card as a standalone line.
                    HowNumbersWorkFooter(includePlanProjection = true)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * The week's data-quality chores as the week card's third row (#1024 D4) — or `null` when the week is
 * clean, which is what keeps the hairline from hanging over an empty section.
 *
 * **Copy in, one affordance out.** The flags, their thresholds and their wording stay owned by the
 * analytics hub ([reviewTexts] resolves the copy, [ReviewList] renders it — Home re-implements
 * neither). What Home changes is only the affordance: the hub's rows open the assign/attest dialogs
 * in place, which live on the Money tab together with the orphan lists they mutate. Home has no
 * standing to host those flows, so it reads the action-FREE resolver and wraps the list in ONE honest
 * affordance — open the place that owns the fix. Structurally it cannot receive a per-row action it
 * would have to strip.
 *
 * The slot lambda is remembered on the resolved rows so a recomposition that changed nothing about
 * the week does not hand [WeekCard] a new composable to run.
 */
@Composable
private fun rememberReviewSlot(
    economics: PeriodEconomics,
    orphanOfferGroups: List<OrphanOfferGroup>,
    onOpen: () -> Unit,
): (@Composable () -> Unit)? {
    val items = reviewTexts(economics = economics, orphanOfferGroups = orphanOfferGroups)
    return remember(items, onOpen) {
        if (items.isEmpty()) null else ({ ReviewRow(items = items, onOpen = onOpen) })
    }
}

/** `NEEDS A LOOK (2)` over the hub's own rendered rows, with one `Review →` for the section. */
@Composable
private fun ReviewRow(items: List<ReviewItem>, onOpen: () -> Unit) {
    DashboardRow(
        actionLabel = stringResource(R.string.dashboard_review_action),
        onClick = onOpen,
        actionAlignment = Alignment.Top,
    ) {
        ReviewList(items = items)
    }
}

/** Ratings as one row of its own card (#1447): its title, then "Open". */
@Composable
private fun RatingsRow(onOpenRatings: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        DashboardRow(
            actionLabel = stringResource(R.string.dashboard_ratings_action),
            onClick = onOpenRatings,
        ) {
            DashboardRowTitle(stringResource(R.string.ratings_screen_title))
        }
    }
}

/**
 * #1457 — Home while a permission step is open: screen reading off gets its own card and button;
 * anything else is "Setup isn't finished" with Continue setup.
 */
@Composable
private fun PermissionGateCard(screenReadingOff: Boolean, onContinue: () -> Unit) {
    StatusCard(
        title = stringResource(
            if (screenReadingOff) R.string.dashboard_screen_permissions_a11y_title
            else R.string.dashboard_screen_permissions_setup_title,
        ),
        subtitle = stringResource(
            if (screenReadingOff) R.string.dashboard_screen_permissions_a11y_subtitle
            else R.string.dashboard_screen_permissions_setup_subtitle,
        ),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        textColor = MaterialTheme.colorScheme.onErrorContainer
    )
    Spacer(Modifier.height(16.dp))
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onContinue,
    ) {
        Text(
            stringResource(
                if (screenReadingOff) R.string.perm_action_accessibility
                else R.string.dashboard_screen_permissions_continue,
            ),
        )
    }
}
