package cloud.trotter.log.gig.feature.bubble.hud.odometer

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatShortDate
import cloud.trotter.log.gig.domain.model.vehicle.vehicleType
import cloud.trotter.log.gig.domain.model.vehicle.OdometerCheck
import cloud.trotter.log.gig.feature.bubble.R

@Composable
internal fun odometerCopy(model: OdometerSheetModel): String = when (val check = model.check) {
    OdometerCheck.NoVehicle -> stringResource(R.string.hud_odometer_no_vehicle)
    OdometerCheck.Empty -> model.last?.let {
        stringResource(R.string.hud_odometer_last, Formats.commaInt(it.readingMiles), formatShortDate(it.occurredAt))
    }.orEmpty()
    is OdometerCheck.First -> stringResource(R.string.hud_odometer_first,
        check.vehicleName ?: model.vehicle?.let { stringResource(vehicleTypeLabel(it.profile.vehicleType)) }.orEmpty())
    is OdometerCheck.Lower -> stringResource(R.string.hud_odometer_lower, Formats.commaInt(check.lastMiles))
    is OdometerCheck.FewerThanTracked -> stringResource(R.string.hud_odometer_fewer,
        Formats.decimal(check.tracked), formatShortDate(check.since), Formats.commaInt(check.delta))
    is OdometerCheck.LargeJump -> stringResource(R.string.hud_odometer_large_jump, Formats.commaInt(check.delta), formatShortDate(check.since))
    is OdometerCheck.Ok -> stringResource(R.string.hud_odometer_delta, Formats.commaInt(check.delta), formatShortDate(check.since))
}
