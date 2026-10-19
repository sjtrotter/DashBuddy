package cloud.trotter.dashbuddy.feature.bubble.hud.odometer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.vehicle.OdometerCheck
import cloud.trotter.dashbuddy.domain.model.vehicle.OdometerReadingLimit
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.feature.bubble.hud.keypad.Keypad

/** Pure entry surface. The app owns navigation and all repository writes. */
@Composable
fun ColumnScope.OdometerSheet(
    model: OdometerSheetModel,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onSave: () -> Unit,
    onAddVehicle: (String) -> Unit,
    onSwitchVehicle: () -> Unit,
    onSelectVehicle: (String) -> Unit,
    onShowAddVehicle: () -> Unit,
    onBack: () -> Unit,
) {
    val page = model.page
    Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
        when (page) {
            OdometerPage.ADD_VEHICLE -> AddVehicleForm(model.saving, onBack, onAddVehicle)
            OdometerPage.VEHICLES -> VehicleSwitcher(model.vehicles, model.vehicle?.id, model.saving, onSelectVehicle, onShowAddVehicle, onBack)
            OdometerPage.READING -> {
                Row(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OdometerTitle(stringResource(R.string.hud_odometer_title), onBack)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(model.vehicle?.name.orEmpty(), Modifier.weight(1f), color = AppTheme.colors.text,
                                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = onSwitchVehicle, enabled = !model.saving, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Outlined.SwapHoriz, stringResource(R.string.hud_odometer_switch), tint = AppTheme.colors.accent)
                            }
                        }
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
                            TextButton(onClick = onShowAddVehicle) { Text(stringResource(R.string.hud_odometer_add_vehicle)) }
                        }
                    }
                    Keypad(onDigit, null, onBackspace)
                }
                Button(onClick = onSave, enabled = (model.readingMiles ?: 0) in 1..OdometerReadingLimit.MAX_MILES && !model.saving,
                    modifier = Modifier.fillMaxWidth().testTag("odometer.save")) {
                    Text(if (model.readingMiles == null) stringResource(R.string.hud_odometer_save_empty)
                        else stringResource(R.string.hud_odometer_save, Formats.commaInt(model.readingMiles)))
                }
            }
        }
        when (model.writeFailed) {
            OdometerWriteFailure.NONE -> Unit
            OdometerWriteFailure.RETRY -> Text(stringResource(R.string.hud_odometer_write_failed), color = AppTheme.colors.warn)
            OdometerWriteFailure.UNREADABLE -> Text(stringResource(R.string.hud_odometer_vehicles_unreadable), color = AppTheme.colors.warn)
        }
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
