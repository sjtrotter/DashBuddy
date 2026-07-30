package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.OfferFilter
import cloud.trotter.dashbuddy.domain.analytics.OfferListing
import cloud.trotter.dashbuddy.domain.analytics.OfferOutcome
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatClockTime

/**
 * "Offers in this window" (#975 / brief §7.4) — the tab's missing feature: the driver's actual
 * offers, filterable (All / Accepted / Declined / Timed out), newest decision first, with a
 * `See all N offers` footer.
 *
 * **Declined and timed-out rows render at reduced opacity** (brief §5). That is a *de-emphasis*, not
 * a hiding: the row keeps every figure, because "what did I turn down" is exactly the question this
 * list exists to answer — the dimming only says "you didn't take this one".
 *
 * **Every economic column is a frozen decision-time figure** — the pay/distance the card quoted and
 * the verdict's own est. $/hr and score (the tab's standing disclosure covers all of them, §9). A
 * #936 no-verdict offer carries nulls, rendered as the em-dash placeholder; a missing figure is
 * never drawn as a zero.
 *
 * Privacy: `offer_records` holds no customer fields, and merchant names are driver-owned display
 * data (Principle 6).
 */
@Composable
fun OffersListCard(
    feed: OffersFeedState,
    onSelectFilter: (OfferFilter) -> Unit,
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.offers_tab_list_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))

        val options = filterOptions()
        AppSegmented(
            options = options.map { it.label },
            selected = options.first { it.filter == feed.filter }.label,
            onSelect = { label ->
                // Selection stays keyed off the enum, resolved against this already-built pairs list
                // (#428 Half A) — never a raw label-string comparison that could collide across locales.
                options.firstOrNull { it.label == label }?.let { onSelectFilter(it.filter) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        if (feed.offers.isEmpty()) {
            EmptyRow(stringResource(R.string.offers_tab_list_empty))
            return@AppCard
        }

        feed.offers.forEachIndexed { index, offer ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            OfferRow(offer)
        }

        Spacer(Modifier.height(12.dp))
        OffersFooter(feed, onShowAll)
    }
}

/**
 * One offer: store + payout on the headline, and a `time · distance · est. rate · score` meta line
 * under it with a status pill on the right.
 *
 * The meta line is assembled from the parts that actually parsed — a null figure is dropped rather
 * than rendered as `— mi`, so an unparsed offer reads as a shorter line instead of a row of
 * placeholders.
 */
@Composable
private fun OfferRow(offer: OfferListing) {
    val c = AppTheme.colors
    val dimmed = offer.outcome != OfferOutcome.ACCEPTED
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) DECLINED_ALPHA else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = offer.storeName ?: stringResource(R.string.offers_tab_row_unknown_store),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = metaLine(offer),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = offer.payAmount?.let { Formats.money(it) } ?: EMPTY_VALUE,
                style = AppTheme.num.smNum,
                color = c.text,
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.height(4.dp))
            OutcomePill(offer.outcome)
        }
    }
}

/** `2:14 PM · 4.2 mi · est. $18.40/hr · score 7.1` — only the parts that exist. */
@Composable
private fun metaLine(offer: OfferListing): String {
    val separator = stringResource(R.string.offers_tab_row_meta_separator)
    val parts = buildList {
        add(formatClockTime(offer.decidedAt))
        offer.distanceMiles?.let {
            add(stringResource(R.string.offers_tab_row_distance_format, Formats.decimal(it, 1)))
        }
        offer.estDollarsPerHour?.let {
            add(stringResource(R.string.offers_tab_row_est_rate_format, Formats.money(it)))
        }
        offer.score?.let {
            add(stringResource(R.string.offers_tab_row_score_format, Formats.decimal(it, 1)))
        }
    }
    return parts.joinToString(separator)
}

/** The status pill — accepted is the affirmative colour, the two "didn't take it" outcomes are not. */
@Composable
private fun OutcomePill(outcome: OfferOutcome?) {
    val c = AppTheme.colors
    val (labelRes, color) = when (outcome) {
        OfferOutcome.ACCEPTED -> R.string.offers_tab_segment_accepted to c.good
        OfferOutcome.DECLINED -> R.string.offers_tab_segment_declined to c.bad
        OfferOutcome.TIMED_OUT -> R.string.offers_tab_segment_timed_out to c.neutral
        null -> R.string.offers_tab_pill_unknown to c.text3
    }
    AppChip(text = stringResource(labelRes), color = color, container = c.surface3)
}

/**
 * `See all N offers` / `Showing all N offers` / `Showing the most recent N of M offers`.
 *
 * The third state is the honest one the ceiling forces: the read clamps a page at
 * [AnalyticsRepository][cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository]'s maximum, so
 * a Lifetime window says what it is showing rather than implying the list is complete (§9).
 */
@Composable
private fun OffersFooter(feed: OffersFeedState, onShowAll: () -> Unit) {
    val c = AppTheme.colors
    val showAllLabel = stringResource(R.string.offers_tab_see_all_format, Formats.commaInt(feed.total))
    when {
        feed.canShowMore -> Text(
            text = showAllLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = c.accent,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = showAllLabel, role = Role.Button) { onShowAll() },
        )

        feed.cappedByCeiling -> Text(
            text = stringResource(
                R.string.offers_tab_showing_capped_format,
                Formats.commaInt(feed.offers.size),
                Formats.commaInt(feed.total),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )

        else -> Text(
            text = stringResource(R.string.offers_tab_showing_all_format, Formats.commaInt(feed.total)),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

/** A filter paired with its resolved label (#428 Half A) — identity stays the enum. */
private data class FilterOption(val filter: OfferFilter, val label: String)

@Composable
private fun filterOptions(): List<FilterOption> = OfferFilter.entries.map { filter ->
    FilterOption(
        filter = filter,
        label = stringResource(
            when (filter) {
                OfferFilter.ALL -> R.string.offers_tab_filter_all
                OfferFilter.ACCEPTED -> R.string.offers_tab_segment_accepted
                OfferFilter.DECLINED -> R.string.offers_tab_segment_declined
                OfferFilter.TIMED_OUT -> R.string.offers_tab_segment_timed_out
            },
        ),
    )
}

/** Declined / timed-out rows are de-emphasised, never hidden (brief §5). */
private const val DECLINED_ALPHA = 0.55f
