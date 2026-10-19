package cloud.trotter.dashbuddy.feature.bubble.hud.odometer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatShortDate
import cloud.trotter.dashbuddy.domain.model.vehicle.LastOdometerReading
import cloud.trotter.dashbuddy.domain.model.vehicle.ReconciledMileage
import cloud.trotter.dashbuddy.feature.bubble.R
import kotlin.math.abs

@Composable
fun OdometerFooter(vehicleName: String?, last: LastOdometerReading?, reconciliation: ReconciledMileage?, onOpen: () -> Unit) {
    if (last == null) return
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp)) {
        Text(stringResource(R.string.hud_odometer_footer, vehicleName ?: EMPTY_VALUE,
            Formats.commaInt(last.readingMiles), formatShortDate(last.occurredAt)),
            color = AppTheme.colors.text2, style = MaterialTheme.typography.labelMedium)
        reconciliation?.let {
            Text(stringResource(R.string.hud_odometer_comparison, Formats.commaInt(it.carMiles),
                Formats.decimal(it.trackedMiles), formatShortDate(it.since)), color = AppTheme.colors.text2, style = MaterialTheme.typography.labelSmall)
            val otherCopy = if (it.otherMiles < 0) R.string.hud_odometer_short else R.string.hud_odometer_other
            Text(stringResource(otherCopy, Formats.decimal(abs(it.otherMiles))),
                color = AppTheme.colors.text2, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val PROMPT_WINDOW_MS = 600_000L

sealed interface PromptAnchor {
    data class DashStart(val startedAt: Long) : PromptAnchor
    data class DashEnd(val endedAt: Long) : PromptAnchor
}

@Composable
fun OdometerPrompt(enabled: Boolean, anchor: PromptAnchor?, last: LastOdometerReading?, onOpen: () -> Unit) {
    val now by rememberNow()
    val show = enabled && when (anchor) {
        is PromptAnchor.DashStart -> now >= anchor.startedAt && now - anchor.startedAt < PROMPT_WINDOW_MS &&
            (last == null || last.occurredAt < anchor.startedAt - PROMPT_WINDOW_MS)
        is PromptAnchor.DashEnd -> last == null || last.occurredAt < anchor.endedAt - PROMPT_WINDOW_MS
        null -> false
    }
    if (show) TextButton(onClick = onOpen) { Text(stringResource(R.string.hud_odometer_prompt)) }
}

@Composable
fun OdometerNotice(model: OdometerNoticeModel) {
    Text(stringResource(R.string.hud_odometer_saved, Formats.commaInt(model.readingMiles)),
        color = AppTheme.colors.text, style = MaterialTheme.typography.bodyMedium)
}
