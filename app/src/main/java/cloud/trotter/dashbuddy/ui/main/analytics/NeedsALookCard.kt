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
 * Which review flags a window raises, and in what order (#973) — pure, Compose-free, so the *gating*
 * can be proved without rendering (the [MoneyWentModel]/[NetDelta] precedent). The copy that
 * describes each flag is a separate concern and lives in [reviewItems].
 *
 * Every threshold here is carried over unchanged from the four standalone callouts this card
 * consolidated: the same money epsilon, the same COUNT gate on the "(No session)" bucket (a pay-less
 * orphan is still a categorizable item), and the same still-OWED gate on the orphan offers (#810 B2
 * F3 — a fully-resolved group stays reachable in the dialog for undo but no longer raises the flag).
 * Consolidation is a layout change; no flag was added, dropped, or re-thresholded.
 */
internal object ReviewFlags {

    /** The review signals, in the order the card lists them. */
    enum class Flag {
        /** Reported pay exceeding the captured deliveries — bonuses/adjustments/a missed capture. */
        UNATTRIBUTED,

        /** The #701 mirror: captured pay exceeding what the platform reported. Display-only. */
        OVER_ATTRIBUTED,

        /** The "(No session)" bucket (#660) — deliveries not tied to any dash. Actionable. */
        NO_SESSION,

        /** Accepted offers with no matching delivery (#810 B2 Tier 2). Actionable. */
        ORPHAN_OFFERS,
    }

    fun of(economics: PeriodEconomics, orphanOfferGroups: List<OrphanOfferGroup>): List<Flag> = buildList {
        if (economics.unattributedPay > UNATTRIBUTED_EPSILON) add(Flag.UNATTRIBUTED)
        if (economics.overAttributedPay > UNATTRIBUTED_EPSILON) add(Flag.OVER_ATTRIBUTED)
        if (economics.noSessionDeliveries > 0) add(Flag.NO_SESSION)
        if (orphanOfferGroups.sumOf { it.owedRemaining } > 0) add(Flag.ORPHAN_OFFERS)
    }
}

/**
 * The window's review rows **as copy only** — text and severity, never an affordance (#1024 D4).
 *
 * This is the resolver Home uses. Home cannot honour the hub's in-place assign/attest dialogs (they
 * live on the Money tab with the orphan lists they mutate), so it must not be *able* to receive an
 * action it would have to strip or, worse, render as a promise it can't keep. Splitting the resolver
 * makes that structural rather than a discipline the next caller has to remember: the copy has one
 * owner, and [ReviewAction] is the hub card's own layer on top of it.
 */
@Composable
internal fun reviewTexts(
    economics: PeriodEconomics,
    orphanOfferGroups: List<OrphanOfferGroup>,
): List<ReviewItem> = reviewRows(economics, orphanOfferGroups).map { it.second }

/**
 * The window's review rows: [ReviewFlags.of] decides WHICH, [reviewRows] resolves each one's copy,
 * and this attaches the hub's own in-place actions.
 */
@Composable
internal fun reviewItems(
    economics: PeriodEconomics,
    orphanOfferGroups: List<OrphanOfferGroup>,
    onOpenNoSession: () -> Unit,
    onOpenOrphanOffers: () -> Unit,
): List<ReviewItem> = reviewRows(economics, orphanOfferGroups).map { (flag, item) ->
    when (flag) {
        ReviewFlags.Flag.UNATTRIBUTED, ReviewFlags.Flag.OVER_ATTRIBUTED -> item
        ReviewFlags.Flag.NO_SESSION -> item.copy(
            action = ReviewAction(
                label = stringResource(R.string.money_tab_no_session_callout_click_label),
                onClick = onOpenNoSession,
            ),
        )
        ReviewFlags.Flag.ORPHAN_OFFERS -> item.copy(
            action = ReviewAction(
                label = stringResource(R.string.money_tab_orphan_offers_callout_click_label),
                onClick = onOpenOrphanOffers,
            ),
        )
    }
}

/** Each raised flag paired with its copy — the one place a flag's wording is resolved. */
@Composable
private fun reviewRows(
    economics: PeriodEconomics,
    orphanOfferGroups: List<OrphanOfferGroup>,
): List<Pair<ReviewFlags.Flag, ReviewItem>> = ReviewFlags.of(economics, orphanOfferGroups).map { flag ->
    flag to when (flag) {
        ReviewFlags.Flag.UNATTRIBUTED -> ReviewItem(
            text = stringResource(
                R.string.money_tab_unattributed_callout_format,
                Formats.money(economics.unattributedPay),
            ),
        )
        // An over-count is a stronger signal than an unattributed bonus/adjustment — the tone the
        // standalone callout carried via badBg.
        ReviewFlags.Flag.OVER_ATTRIBUTED -> ReviewItem(
            text = stringResource(
                R.string.money_tab_over_attributed_callout_format,
                Formats.money(economics.overAttributedPay),
            ),
            severe = true,
        )
        // Already inside BOTH gross and net (#660 piece 1), so this row is the visible data-quality
        // signal, not a missing figure. Tapping opens the categorize flow, which heals the overlap.
        ReviewFlags.Flag.NO_SESSION -> ReviewItem(
            text = stringResource(
                R.string.money_tab_no_session_callout_format,
                Formats.money(economics.noSessionPay),
                Formats.commaInt(economics.noSessionDeliveries),
                if (economics.noSessionDeliveries == 1) {
                    stringResource(R.string.time_tab_delivery_singular)
                } else {
                    stringResource(R.string.time_tab_delivery_plural)
                },
            ),
        )
        ReviewFlags.Flag.ORPHAN_OFFERS -> ReviewItem(
            text = stringResource(
                R.string.money_tab_orphan_offers_callout_format,
                Formats.commaInt(orphanOfferGroups.sumOf { it.owedRemaining }),
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
    AppCard(modifier = modifier.fillMaxWidth()) {
        ReviewList(items = items)
    }
}

/**
 * The card's contents WITHOUT its container (#1024 D4) — the count title over the flag rows.
 *
 * Extracted because Home renders the same list as a hairline row inside its merged week card rather
 * than as a card of its own. The count-title format, the warn header tone and the severe→bad row tone
 * therefore have ONE owner across both surfaces; two copies of "what a review row looks like" is
 * precisely the drift Principle 5 exists to prevent, and this one would have had to be kept in step
 * across a module boundary.
 */
@Composable
internal fun ReviewList(items: List<ReviewItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val c = AppTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
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
