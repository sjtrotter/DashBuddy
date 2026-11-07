package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.AppCard
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck

/**
 * The shift's checks under the money check, one line each: "1 accepted offer has no delivery recorded" for the
 * offers with no delivery together, one line per job with more accepted offers than deliveries. The money check's
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
}

/** True when [checks] has a line for [ShiftChecksBlock]. */
internal fun hasCheckLines(checks: List<ShiftCheck>): Boolean = checks.any { it !is ShiftCheck.MoneyDiffers }
