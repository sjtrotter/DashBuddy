package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.format.Formats

/**
 * One review item on the "Needs a look" card (#973 / brief §4.2) — the same underlying flags the
 * Money tab used to render as a stack of separate amber callouts, now rows of one card.
 *
 * [action] is the row's own affordance (the label the row shows and the lambda it fires) or null for
 * an item that is purely informational today. Consolidation is a *layout* change only: no flag was
 * added, dropped, or re-thresholded, and each actionable row keeps exactly the action it had.
 */
internal data class ReviewItem(
    val text: String,
    val action: ReviewAction? = null,
    /** True for the stronger signal (an over-count) — rendered in the bad tone rather than warn. */
    val severe: Boolean = false,
)

internal data class ReviewAction(val label: String, val onClick: () -> Unit)

/**
 * Build the window's review items in a fixed order (#973). Compose-aware only because the copy lives
 * in resources; the *decisions* (which flag fires, at what threshold) are unchanged from the callouts
 * this replaced — same epsilon on the money figures, same count gate on the orphan buckets.
 */
@Composable
internal fun reviewItems(
    economics: PeriodEconomics,
    orphanOfferGroups: List<OrphanOfferGroup>,
    onOpenNoSession: () -> Unit,
    onOpenOrphanOffers: () -> Unit,
): List<ReviewItem> = buildList {
    if (economics.unattributedPay > UNATTRIBUTED_EPSILON) {
        add(
            ReviewItem(
                text = stringResource(
                    R.string.money_tab_unattributed_callout_format,
                    Formats.money(economics.unattributedPay),
                ),
            ),
        )
    }
    // Mirror signal (#701): attributed pay exceeded the reported total — display-only, never folded
    // into netProfit/unattributedPay. Marked severe (an over-count is a stronger review flag than an
    // unattributed bonus/adjustment) — the tone the standalone callout carried via badBg.
    if (economics.overAttributedPay > UNATTRIBUTED_EPSILON) {
        add(
            ReviewItem(
                text = stringResource(
                    R.string.money_tab_over_attributed_callout_format,
                    Formats.money(economics.overAttributedPay),
                ),
                severe = true,
            ),
        )
    }
    // "(No session)" bucket (#660): deliveries whose source event carried no sessionId at all —
    // already inside gross AND net, so this is the visible data-quality signal, not a missing figure.
    // Gated on the COUNT, not the pay: a pay-less orphan is still a categorizable item.
    if (economics.noSessionDeliveries > 0) {
        val deliveryWord = if (economics.noSessionDeliveries == 1) {
            stringResource(R.string.time_tab_delivery_singular)
        } else {
            stringResource(R.string.time_tab_delivery_plural)
        }
        add(
            ReviewItem(
                text = stringResource(
                    R.string.money_tab_no_session_callout_format,
                    Formats.money(economics.noSessionPay),
                    Formats.commaInt(economics.noSessionDeliveries),
                    deliveryWord,
                ),
                action = ReviewAction(
                    label = stringResource(R.string.money_tab_no_session_callout_click_label),
                    onClick = onOpenNoSession,
                ),
            ),
        )
    }
    // Orphan-offer mismatches (#810 B2 Tier 2), gated on the still-OWED count (F3 — a fully-resolved
    // group stays reachable in the dialog for undo but no longer drives this row).
    val orphanCount = orphanOfferGroups.sumOf { it.owedRemaining }
    if (orphanCount > 0) {
        add(
            ReviewItem(
                text = stringResource(
                    R.string.money_tab_orphan_offers_callout_format,
                    Formats.commaInt(orphanCount),
                ),
                action = ReviewAction(
                    label = stringResource(R.string.money_tab_orphan_offers_callout_click_label),
                    onClick = onOpenOrphanOffers,
                ),
            ),
        )
    }
}

/**
 * "Needs a look (2)" (#973 / brief §4.2) — the consolidated review card. Four stacked amber strips
 * read as four alarms; one card with a count in its header reads as one list of chores, which is what
 * they are. Renders nothing at all when the window is clean.
 */
@Composable
internal fun NeedsALookCard(items: List<ReviewItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val c = AppTheme.colors
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.money_tab_needs_a_look_title_format, Formats.commaInt(items.size)),
            style = MaterialTheme.typography.labelMedium,
            color = c.warn,
        )
        Spacer(Modifier.height(10.dp))
        items.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            val action = item.action
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .let { base ->
                        if (action == null) {
                            base
                        } else {
                            base.clickable(onClickLabel = action.label, role = Role.Button) { action.onClick() }
                        }
                    },
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.severe) c.bad else c.text2,
                    )
                }
                if (action != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = c.accent,
                    )
                }
            }
        }
    }
}
