package cloud.trotter.dashbuddy.feature.bubble.hud.odometer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatShortDate
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.model.vehicle.LastOdometerReading
import cloud.trotter.dashbuddy.domain.model.vehicle.ReconciledMileage
import cloud.trotter.dashbuddy.feature.bubble.R
import kotlin.math.abs

@Composable
fun OdometerFooter(vehicle: Vehicle?, last: LastOdometerReading?, reconciliation: ReconciledMileage?, onOpen: () -> Unit) {
    if (last == null) return
    val vehicleName = vehicle?.displayName()
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

@Composable
fun OdometerNotice(model: OdometerNoticeModel) {
    Text(stringResource(R.string.hud_odometer_saved, Formats.commaInt(model.readingMiles)),
        color = AppTheme.colors.text, style = MaterialTheme.typography.bodyMedium)
}
