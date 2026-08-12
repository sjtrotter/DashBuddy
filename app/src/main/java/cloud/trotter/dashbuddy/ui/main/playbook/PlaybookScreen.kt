package cloud.trotter.dashbuddy.ui.main.playbook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.ui.components.HowNumbersWorkFooter
import cloud.trotter.dashbuddy.ui.main.plan.AreaDemandRow

/**
 * **Playbook** (#1024 section C) — the app's third destination: *Home = today · Analytics = the past ·
 * Playbook = the next move.*
 *
 * It exists because the surfaces it holds all answer **what should I do**, and all of them are
 * lifetime- or week-scoped by definition. Inside the period-first analytics hub they read as strangers:
 * the pager above them moves and nothing here changes, which looks like a bug and is actually the
 * design. Given their own destination, that scope is the point rather than an exception.
 *
 * Top to bottom:
 *  1. **This week's plan**, with live progress — the visible half of the Sunday grading loop;
 *  2. **When you earn** — the lifetime heatmap (Rate/Hours), with the plan's picked cells outlined, so
 *     the plan is visibly derived from the driver's own record;
 *  3. **Where you earn** — the store leaderboard (net / wait / recent);
 *  4. the locked **Demand around you** row — designed in, carrying no data, so the screen doesn't need
 *     reworking when it arrives;
 *  5. one **"How these numbers work"** disclosure for the whole screen (#1024 rule 2).
 *
 * UDF: immutable state down, one navigation lambda up. No reads of its own — every source already ships
 * (see [PlaybookViewModel]). The only ticking value on the screen is the plan card's own hour boundary,
 * derived at that composable (Reactive-UI rule 2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybookScreen(
    onBack: () -> Unit,
    onOpenPlan: () -> Unit,
    viewModel: PlaybookViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.playbook_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back),
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
                .fillMaxSize(),
        ) {
            // F1 (#1024 review): nothing renders until the first read-model emission. Every block
            // below has an honest empty state, and on a cold open they would ALL be true for a frame —
            // "No plan saved for this week" over a plan the driver saved yesterday is not an honest
            // empty state, it is a wrong one shown briefly. Waiting is the honest option (§9).
            if (uiState.loading) return@Column

            PlanProgressCard(
                grade = uiState.planGrade,
                onOpenPlan = onOpenPlan,
            )
            Spacer(Modifier.height(16.dp))

            // Declares the scope of the two cards below it — both read the whole record and are not a
            // view of any selected period (#979).
            AllTimeBadge()
            Spacer(Modifier.height(12.dp))

            WhenYouEarnCard(heatmap = uiState.heatmap, plan = uiState.savedPlan)
            Spacer(Modifier.height(16.dp))

            WhereYouEarnCard(cards = uiState.storeCards)
            Spacer(Modifier.height(16.dp))

            AreaDemandRow()
            Spacer(Modifier.height(20.dp))

            HowNumbersWorkFooter()
            Spacer(Modifier.height(24.dp))
        }
    }
}
