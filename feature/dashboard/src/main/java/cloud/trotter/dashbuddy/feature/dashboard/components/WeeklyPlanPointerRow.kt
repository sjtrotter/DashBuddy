package cloud.trotter.dashbuddy.feature.dashboard.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.SavedWeeklyPlan
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.dashboard.R

/**
 * `Your week is planned · 12 target hours across 4 windows · $280 projected` — Home's pointer at the
 * Weekly Plan (#981 / brief §2 row 3), filling the seam #977 left.
 *
 * **Only rendered when a plan exists** (the caller decides, so this composable never has to represent
 * absence). The three figures come straight off the frozen [SavedWeeklyPlan] — the numbers the driver
 * committed to, not a re-derivation that might have moved under them since — and "projected" is the
 * word the row uses, never "you'll earn" (§9).
 *
 * Data in, lambda out; no Hilt, no repository — the feature-module contract.
 */
@Composable
fun WeeklyPlanPointerRow(
    plan: SavedWeeklyPlan,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dashboard_weekly_plan_pointer_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.text,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.dashboard_weekly_plan_pointer_detail_format,
                        plan.totalHours,
                        plan.windows.size,
                        Formats.money0(plan.projectedKept),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
            }
            // A text affordance, not a chevron icon: this module deliberately carries no
            // material-icons dependency (the honest-deps doctrine), and `Recap →` next door already
            // set the vocabulary.
            Text(
                text = stringResource(R.string.dashboard_weekly_plan_pointer_action),
                style = MaterialTheme.typography.labelLarge,
                color = c.accent,
            )
        }
    }
}
