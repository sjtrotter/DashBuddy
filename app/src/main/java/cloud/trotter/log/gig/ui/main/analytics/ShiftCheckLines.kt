package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.AppCard
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.checks.MoneyCheck
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.format.Formats

/**
 * The shift's checks under the money check, one line each: "1 accepted offer has no delivery recorded" for the
 * offers with no delivery together, one line per job with more accepted offers than deliveries, and (#1119 S2) one
 * line per job whose work tied two or more offers at its store ("1 job could be from 2 offers"). The money check's
 * own line stays in its block. Nothing when there is no such check.
 */
@Composable
internal fun ShiftChecksBlock(checks: List<ShiftCheck>) {
    val lines = shiftCheckLines(checks)
    if (lines.isEmpty()) return
    AppCard(Modifier.fillMaxWidth()) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.text) }
    }
}

/** The lines [ShiftChecksBlock] shows: every check but the money check, whose block shows it. */
@Composable
internal fun shiftCheckLines(checks: List<ShiftCheck>): List<String> = buildList {
    val noDelivery = checks.count { it is ShiftCheck.NoDelivery }
    if (noDelivery > 0) add(pluralStringResource(R.plurals.shift_check_no_delivery, noDelivery, noDelivery))
    checks.filterIsInstance<ShiftCheck.MoreAcceptsThanDeliveries>().forEach {
        add(pluralStringResource(R.plurals.shift_check_more_accepts, it.delivered, it.accepted, it.delivered))
    }
    checks.filterIsInstance<ShiftCheck.OfferNotMatched>().forEach {
        val n = it.offerEventSequenceIds.size
        add(pluralStringResource(R.plurals.shift_check_offer_unmatched, n, n))
    }
}

/** True when [checks] has a line for [ShiftChecksBlock]. */
internal fun hasCheckLines(checks: List<ShiftCheck>): Boolean = checks.any { it !is ShiftCheck.MoneyDiffers }

/** "DoorDash total $23.50 higher" or "Delivery pay $23.50 higher": the money check's one line. */
@Composable
internal fun higherLine(higher: MoneyCheck.Higher, amount: Double, appName: String): String {
    val label = when (higher) {
        MoneyCheck.Higher.APP_TOTAL -> stringResource(R.string.shift_page_app_total_format, appName)
        MoneyCheck.Higher.DELIVERY_PAY -> stringResource(R.string.shift_page_delivery_pay)
    }
    return stringResource(R.string.shift_page_higher_format, label, Formats.money(amount))
}

/** Every line for [checks]: the money line first, then [shiftCheckLines]. Recap's Shifts to check. */
@Composable
internal fun allCheckLines(checks: List<ShiftCheck>, appName: String): List<String> =
    checks.filterIsInstance<ShiftCheck.MoneyDiffers>().map { higherLine(it.higher, it.amount, appName) } +
        shiftCheckLines(checks)

/**
 * Where a link to a shift with [checks] lands: the first offer with no delivery, else the first job with more accepts
 * than deliveries, else the first job a tie left unmatched (#1119 S2), else the page's top (null), where the money
 * check shows.
 */
internal fun landingRowOf(checks: List<ShiftCheck>): String? =
    checks.firstNotNullOfOrNull { (it as? ShiftCheck.NoDelivery)?.let { check -> offerRowKey(check.offerEventSequenceId) } }
        ?: checks.firstNotNullOfOrNull { (it as? ShiftCheck.MoreAcceptsThanDeliveries)?.let { check -> jobRowKey(check.jobId) } }
        ?: checks.firstNotNullOfOrNull { (it as? ShiftCheck.OfferNotMatched)?.let { check -> jobRowKey(check.jobId) } }
