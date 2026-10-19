package cloud.trotter.dashbuddy.feature.bubble.hud.odometer

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatMonthDay
import cloud.trotter.dashbuddy.domain.model.vehicle.OdometerCheck
import cloud.trotter.dashbuddy.feature.bubble.R
import java.time.Instant
import java.time.ZoneId

internal fun odometerDate(at: Long): String = formatMonthDay(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate())

internal fun odometerMiles(value: Double): String =
    if (value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) Formats.commaInt(value.toInt())
    else Formats.decimal(value)

@Composable
internal fun odometerCopy(model: OdometerSheetModel): String = when (val check = model.check) {
    OdometerCheck.NoVehicle -> stringResource(R.string.hud_odometer_no_vehicle)
    OdometerCheck.Empty -> model.last?.let {
        stringResource(R.string.hud_odometer_last, Formats.commaInt(it.readingMiles), odometerDate(it.occurredAt))
    }.orEmpty()
    is OdometerCheck.First -> stringResource(R.string.hud_odometer_first, check.vehicleName)
    is OdometerCheck.Lower -> stringResource(R.string.hud_odometer_lower, Formats.commaInt(check.lastMiles))
    is OdometerCheck.FewerThanTracked -> stringResource(R.string.hud_odometer_fewer,
        odometerMiles(check.tracked), odometerDate(check.since), Formats.commaInt(check.delta))
    is OdometerCheck.LargeJump -> stringResource(R.string.hud_odometer_delta, Formats.commaInt(check.delta), odometerDate(check.since))
    is OdometerCheck.Ok -> stringResource(R.string.hud_odometer_delta, Formats.commaInt(check.delta), odometerDate(check.since))
}
