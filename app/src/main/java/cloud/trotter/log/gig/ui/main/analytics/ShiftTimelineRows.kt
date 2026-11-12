package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.AppCard
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.ANALYTICS_MONEY_EPSILON
import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.analytics.timeline.AddOnLine
import cloud.trotter.log.gig.domain.analytics.timeline.DeliveryLine
import cloud.trotter.log.gig.domain.analytics.timeline.JobCard
import cloud.trotter.log.gig.domain.analytics.timeline.JobState
import cloud.trotter.log.gig.domain.analytics.timeline.OfferLine
import cloud.trotter.log.gig.domain.analytics.timeline.PassedOffer
import cloud.trotter.log.gig.domain.analytics.timeline.PassedRun
import cloud.trotter.log.gig.domain.analytics.timeline.PaySource
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatClockTime

/** The test tag of a job card's trailing slot, which holds only money the dasher was paid. */
internal const val JOB_CARD_TRAILING_TAG = "shift_job_trailing"

private const val SEPARATOR = " · "

/**
 * A job: line one the stores as recorded with the paid total right-aligned ("est." when estimated); the offer and
 * add-on lines, the delivery lines, the cash line and the state as supporting text. A one-drop job folds its
 * delivery into one line with no second pay. Tapping opens the job's sheet. The Undo of "It was unassigned" shows only
 * with an [onUndoMarked] handler.
 */
@Composable
internal fun JobCardRow(card: JobCard, onOpen: () -> Unit, onUndoMarked: (() -> Unit)? = null) {
    val c = AppTheme.colors
    AppCard(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(storesText(card.stores), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.text)
            if (card.deliveries.isNotEmpty()) {
                Text(
                    card.paidTotal?.let { moneyText(it, card.paidTotalEstimated) } ?: EMPTY_VALUE,
                    Modifier.padding(start = 12.dp).testTag(JOB_CARD_TRAILING_TAG),
                    style = AppTheme.num.smNum,
                    color = c.text,
                )
            }
        }
        val supporting = buildList {
            val offerLine = card.offerLine
            when {
                offerLine != null -> add(offerText(offerLine))
                card.addOns.isEmpty() -> add(stringResource(R.string.shift_page_offered_format, EMPTY_VALUE))
            }
            card.addOns.forEach { add(addOnText(it)) }
            val single = card.deliveries.size == 1
            card.deliveries.forEach { add(deliveryText(it, withStore = !single, withPay = !single)) }
            card.cashTips?.takeIf { it > ANALYTICS_MONEY_EPSILON }?.let {
                add(stringResource(R.string.shift_page_cash_format, Formats.money(it)))
            }
            when (card.state) {
                JobState.ACCEPTED_NO_DELIVERY_YET -> add(stringResource(R.string.shift_page_accepted))
                JobState.NO_DELIVERY -> add(stringResource(R.string.shift_page_no_delivery))
                JobState.UNASSIGNED -> add(
                    card.unassignedAt?.let { stringResource(R.string.shift_page_unassigned_at_format, formatClockTime(it)) }
                        ?: stringResource(R.string.shift_page_unassigned),
                )
                JobState.MARKED_UNASSIGNED -> add(stringResource(R.string.shift_page_marked_unassigned))
                JobState.NORMAL -> Unit
            }
        }
        supporting.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.text2) }
        // The dasher's "It was unassigned" can be taken back here; the offer is open again once it folds. The search's
        // results pass no handler: the Undo lives on the shift page.
        if (onUndoMarked != null && card.state == JobState.MARKED_UNASSIGNED && card.markedOfferSeqs.isNotEmpty()) {
            TextButton(onClick = onUndoMarked) { Text(stringResource(R.string.shift_page_undo)) }
        }
    }
}

/** A collapsed or open run of passed offers: its counts, the span under it; tapping toggles it. */
@Composable
internal fun PassedRunRow(run: PassedRun, expanded: Boolean, onToggle: () -> Unit) {
    val c = AppTheme.colors
    val state = stringResource(if (expanded) R.string.shift_page_run_expanded else R.string.shift_page_run_collapsed)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(runCountsText(run), style = MaterialTheme.typography.bodyMedium, color = c.text)
            Text(spanText(run.from, run.to), style = MaterialTheme.typography.bodyMedium, color = c.text2)
        }
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = c.text2)
    }
}

/**
 * One passed offer, a thin row on the background: "4:58 PM Chick-fil-A · Declined · $6.50 · 4.1 mi". [modifier] comes
 * first in the row's chain, so a landing highlight's background fills the row under its padding.
 */
@Composable
internal fun PassedOfferRow(offer: PassedOffer, modifier: Modifier = Modifier) {
    val outcome = stringResource(
        when (offer.outcome) {
            OfferOutcome.TIMED_OUT -> R.string.shift_page_outcome_expired
            OfferOutcome.NOT_TAKEN -> R.string.shift_page_outcome_not_taken
            else -> R.string.shift_page_outcome_declined
        },
    )
    val text = listOfNotNull(
        "${formatClockTime(offer.at)} ${storesText(offer.stores)}",
        outcome,
        offer.pay?.let { Formats.money(it) },
        offer.miles?.let { milesText(it) },
    ).joinToString(SEPARATOR)
    Text(
        text,
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.text,
    )
}

@Composable
internal fun storesText(stores: String?): String = stores?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_detail_unknown_store)

@Composable
private fun milesText(miles: Double): String = stringResource(R.string.shift_page_miles_format, Formats.decimal(miles))

/** "Offered $9.75 · 3.1 mi". */
@Composable
internal fun offerText(line: OfferLine): String = listOfNotNull(
    stringResource(R.string.shift_page_offered_format, line.pay?.let { Formats.money(it) } ?: EMPTY_VALUE),
    line.miles?.let { milesText(it) },
).joinToString(SEPARATOR)

/** "Added +$4.00 · +1.8 mi" for an incremental quote, "Added, new total $13.75 · 4.9 mi" for a total one. */
@Composable
internal fun addOnText(addOn: AddOnLine): String = when (addOn) {
    is AddOnLine.Incremental -> listOfNotNull(
        stringResource(R.string.shift_page_added_format, addOn.deltaPay?.let { Formats.money(it) } ?: EMPTY_VALUE),
        addOn.deltaMiles?.let { stringResource(R.string.shift_page_added_miles_format, Formats.decimal(it)) },
    )
    is AddOnLine.NewTotal -> listOfNotNull(
        stringResource(R.string.shift_page_added_new_total_format, addOn.pay?.let { Formats.money(it) } ?: EMPTY_VALUE),
        addOn.miles?.let { milesText(it) },
    )
    is AddOnLine.Offered -> listOfNotNull(
        stringResource(R.string.shift_page_offered_format, addOn.pay?.let { Formats.money(it) } ?: EMPTY_VALUE),
        addOn.miles?.let { milesText(it) },
    )
}.joinToString(SEPARATOR)

/** "6:10 PM · Five Guys · Receipt split · $5.50": the store and pay only when the job has more than one drop. */
@Composable
internal fun deliveryText(line: DeliveryLine, withStore: Boolean, withPay: Boolean): String = listOfNotNull(
    formatClockTime(line.at),
    line.store?.takeIf { withStore && it.isNotBlank() },
    sourceText(line.source),
    if (withPay) line.pay?.let { moneyText(it, line.estimated) } ?: EMPTY_VALUE else null,
).joinToString(SEPARATOR)

@Composable
internal fun sourceText(source: PaySource): String = stringResource(
    when (source) {
        PaySource.RECEIPT -> R.string.shift_page_source_receipt
        PaySource.RECEIPT_SPLIT -> R.string.shift_page_source_receipt_split
        PaySource.OFFER_AMOUNT_EST -> R.string.shift_page_source_offer_estimate
        PaySource.NO_PAY_FOUND -> R.string.shift_page_source_no_pay
        PaySource.YOU_ENTERED -> R.string.shift_page_source_you_entered
    },
)

/**
 * One kind: its plural ("3 declined offers"). Two or more (#1119 S2 adds not taken): the non-zero short forms joined
 * with ", " ("2 declined, 1 not taken").
 */
@Composable
private fun runCountsText(run: PassedRun): String {
    val kinds = listOf(
        Triple(run.declined, R.plurals.shift_page_run_declined, R.string.shift_page_run_short_declined),
        Triple(run.expired, R.plurals.shift_page_run_expired, R.string.shift_page_run_short_expired),
        Triple(run.notTaken, R.plurals.shift_page_run_not_taken, R.string.shift_page_run_short_not_taken),
    ).filter { it.first > 0 }
    kinds.singleOrNull()?.let { (n, plural, _) -> return pluralStringResource(plural, n, n) }
    return kinds.map { (n, _, short) -> stringResource(short, n) }.joinToString(", ")
}

@Composable
private fun spanText(from: Long, to: Long): String =
    if (formatClockTime(from) == formatClockTime(to)) formatClockTime(from)
    else stringResource(R.string.shift_page_span_format, formatClockTime(from), formatClockTime(to))
