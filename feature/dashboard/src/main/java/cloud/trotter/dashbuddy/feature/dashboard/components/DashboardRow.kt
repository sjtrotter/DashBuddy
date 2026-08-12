package cloud.trotter.dashbuddy.feature.dashboard.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * The row vocabulary Home's dense cards are built from (#1024 section D) — ONE owner for the
 * hairline, the tappable row scaffold, and the two text tones a row uses.
 *
 * It exists because the alternative already failed inside a single review round: the first cut of
 * the merged cards hand-rolled each row and the styling drifted immediately (an 8 dp arrangement
 * against a 10 dp spacer, a 2 dp title gap against a 4 dp one) — in one diff, between two rows
 * written on the same afternoon. Per-consumer copies of a layout constant do not hold (Principle 5),
 * so the constants live here and the consumers pass content.
 *
 * Everything here takes plain strings, booleans and lambdas — no `:app` type crosses the boundary,
 * which is what lets the host's own review row (whose copy is owned by the analytics hub) use the
 * same scaffold as this module's week and plan rows.
 */

/** The hairline between two rows of one card, with the padding that belongs to it. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Spacer(Modifier.height(ROW_GAP))
    HorizontalDivider(modifier = modifier, color = AppTheme.colors.line)
    Spacer(Modifier.height(ROW_GAP))
}

/**
 * A tappable row: content on the left, an accent action label on the right, the whole row clickable
 * and announced with that label.
 *
 * A text affordance rather than a chevron icon — this module deliberately carries no material-icons
 * dependency (the honest-deps doctrine), and `Recap →` set the vocabulary the others follow.
 *
 * [actionAlignment] exists because a two-line row wants its label centred while a tall row (a hero
 * figure over a delta over a sparkline) wants it at the top, beside the thing it acts on.
 */
@Composable
fun DashboardRow(
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = actionLabel, role = Role.Button) { onClick() },
        verticalAlignment = actionAlignment,
    ) {
        Column(Modifier.weight(1f), content = content)
        Spacer(Modifier.width(ACTION_GAP))
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.colors.accent,
        )
    }
}

/**
 * A row's heading. [warn] raises it to the review tone — the one the analytics hub's card already
 * uses for "needs a look", so a flagged row reads the same wherever it is rendered.
 */
@Composable
fun DashboardRowTitle(text: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = if (warn) AppTheme.colors.warn else AppTheme.colors.text3,
    )
}

/**
 * A supporting line under a title. [severe] is the stronger signal (an over-count, not a note) and
 * takes the bad tone — the same distinction the hub's review card draws.
 */
@Composable
fun DashboardRowLine(text: String, modifier: Modifier = Modifier, severe: Boolean = false) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = if (severe) AppTheme.colors.bad else AppTheme.colors.text2,
    )
}

/** The gap above and below a hairline, and between a title and its lines. */
val ROW_GAP = 12.dp

/** The gap between a row's content and its action label. */
private val ACTION_GAP = 10.dp

/** The gap between a row's title and the first line under it. */
val TITLE_GAP = 4.dp
