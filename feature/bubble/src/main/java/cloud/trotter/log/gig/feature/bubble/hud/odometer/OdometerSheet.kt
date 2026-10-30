package cloud.trotter.log.gig.feature.bubble.hud.odometer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.model.vehicle.OdometerCheck
import cloud.trotter.log.gig.domain.model.vehicle.OdometerReadingLimit
import cloud.trotter.log.gig.feature.bubble.R
import cloud.trotter.log.gig.feature.bubble.hud.keypad.Keypad
import cloud.trotter.log.gig.feature.bubble.hud.keypad.KeypadFrame

/** Pure entry surface. The app owns navigation and all repository writes. */
@Composable
fun ColumnScope.OdometerSheet(
    model: OdometerSheetModel,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onSave: () -> Unit,
    vehicleChip: @Composable () -> Unit,
    onShowAddVehicle: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().weight(1f).testTag("odometer.sheet").padding(horizontal = 12.dp, vertical = 8.dp)) {
        when (model.writeFailed) {
            OdometerWriteFailure.NONE -> Unit
            OdometerWriteFailure.RETRY -> Text(stringResource(R.string.hud_odometer_write_failed), color = AppTheme.colors.warn)
            OdometerWriteFailure.UNREADABLE -> Text(stringResource(R.string.hud_odometer_vehicles_unreadable), color = AppTheme.colors.warn)
        }
        val canSave = model.vehicle != null && OdometerReadingLimit.isValid(model.readingMiles ?: 0) && !model.saving
        if (canSave && !model.gpsLoaded) {
            Text(stringResource(R.string.hud_odometer_gps_loading), color = AppTheme.colors.text2,
                style = MaterialTheme.typography.labelSmall)
        }
        KeypadFrame(title = null, block = {
            Row(Modifier.wrapContentHeight(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Row weight shares width only; the scroll content needs its natural height.
                Column(Modifier.weight(1f).wrapContentHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OdometerTitle(stringResource(R.string.hud_odometer_title), onBack)
                    vehicleChip()
                    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp)
                        .background(AppTheme.colors.surface3, RoundedCornerShape(12.dp))
                        .border(1.dp, AppTheme.colors.accent, RoundedCornerShape(12.dp)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(model.readingMiles?.let(Formats::commaInt) ?: EMPTY_VALUE,
                            Modifier.weight(1f).testTag("odometer.reading"), style = AppTheme.num.mdNum,
                            color = AppTheme.colors.text, textAlign = TextAlign.End)
                        Text(stringResource(R.string.hud_state_mi), color = AppTheme.colors.text2, style = MaterialTheme.typography.labelSmall)
                    }
                    val warning = model.check is OdometerCheck.Lower || model.check is OdometerCheck.FewerThanTracked || model.check is OdometerCheck.LargeJump
                    Text(odometerCopy(model), color = if (warning) AppTheme.colors.warn else AppTheme.colors.text3,
                        style = MaterialTheme.typography.labelSmall)
                    if (model.check is OdometerCheck.FewerThanTracked) {
                        TextButton(onClick = onShowAddVehicle, enabled = !model.saving) { Text(stringResource(R.string.hud_odometer_add_vehicle)) }
                    }
                }
                Keypad(onDigit, null, onBackspace, Modifier.align(Alignment.Bottom))
            }
        }, button = {
            Button(onClick = onSave, enabled = canSave && model.gpsLoaded,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag("odometer.save")) {
                Text(if (model.readingMiles == null) stringResource(R.string.hud_odometer_save_empty)
                    else stringResource(R.string.hud_odometer_save, Formats.commaInt(model.readingMiles)), maxLines = 2)
            }
        })
    }
}

@Composable
internal fun OdometerTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.hud_odometer_back), tint = AppTheme.colors.text2)
        }
        Icon(Icons.Outlined.Speed, null, tint = AppTheme.colors.text, modifier = Modifier.size(22.dp))
        Text(title, color = AppTheme.colors.text, style = MaterialTheme.typography.titleSmall)
    }
}
