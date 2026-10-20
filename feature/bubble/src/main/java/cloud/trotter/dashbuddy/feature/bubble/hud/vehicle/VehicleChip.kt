package cloud.trotter.dashbuddy.feature.bubble.hud.vehicle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.model.vehicle.vehicleType
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.displayName
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.vehicleTypeIcon
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.vehicleTypeLabel

data class VehicleChipModel(
    val shown: Vehicle?,
    val vehicles: List<Vehicle>,
    val activeId: String?,
    val unavailable: Boolean,
) {
    /**
     * The odometer sheet's chip names the ACTIVE vehicle, the one a saved reading is recorded against,
     * never the dash's stamped vehicle (review r1: they can differ, and a reading must not show one car
     * and save to another).
     */
    fun forActive(): VehicleChipModel = copy(shown = vehicles.firstOrNull { it.id == activeId })
}

data class VehicleChipInput(
    val active: Vehicle?,
    val dashVehicle: Vehicle?,
    val dashLive: Boolean,
    val vehicles: List<Vehicle>,
    val readable: Boolean,
)

object VehicleChipMapper {
    fun map(input: VehicleChipInput?): VehicleChipModel? = when {
        input == null -> null
        !input.readable -> VehicleChipModel(null, emptyList(), null, unavailable = true)
        else -> VehicleChipModel(if (input.dashLive) input.dashVehicle else input.active,
            input.vehicles.filter { it.retiredAt == null }, input.active?.id, unavailable = false)
    }
}

fun chipName(name: String, max: Int = 12): String {
    require(max >= 1)
    return if (name.codePointCount(0, name.length) <= max) name
    else name.substring(0, name.offsetByCodePoints(0, max - 1)) + "…"
}

@Composable
fun VehicleChip(
    model: VehicleChipModel,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = AppTheme.colors
    val name = when {
        model.unavailable -> stringResource(R.string.hud_vehicle_unavailable)
        model.shown == null -> stringResource(R.string.hud_vehicle_none)
        else -> model.shown.displayName()
    }
    val description = stringResource(R.string.hud_vehicle_cd_change, name)
    val shape = RoundedCornerShape(16.dp)
    Box {
        Row(
            Modifier.testTag("hud.vehicle.chip")
                .semantics(mergeDescendants = true) { contentDescription = description }
                .then(if (model.unavailable) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
                .minimumInteractiveComponentSize()
                .height(32.dp).background(colors.surface2, shape).border(1.dp, colors.lineStrong, shape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val type = model.shown?.profile?.vehicleType
            Icon(type?.let(::vehicleTypeIcon) ?: Icons.Outlined.DirectionsCar,
                contentDescription = type?.let { stringResource(vehicleTypeLabel(it)) },
                tint = if (type == null) colors.text3 else colors.text2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(chipName(name), style = MaterialTheme.typography.labelLarge,
                color = if (model.shown == null) colors.text2 else colors.text, maxLines = 1)
            if (!model.unavailable) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.ExpandMore, null, tint = colors.text2, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = menuOpen && !model.unavailable, onDismissRequest = onDismissMenu,
            modifier = Modifier.testTag("hud.vehicle.menu")) {
            model.vehicles.forEach { vehicle ->
                val checked = vehicle.id == model.activeId
                DropdownMenuItem(
                    text = { Text(vehicle.displayName()) },
                    onClick = { onSelect(vehicle.id) },
                    modifier = Modifier.testTag("hud.vehicle.option.${vehicle.id}").semantics { selected = checked },
                    leadingIcon = { Icon(vehicleTypeIcon(vehicle.profile.vehicleType),
                        stringResource(vehicleTypeLabel(vehicle.profile.vehicleType))) },
                    trailingIcon = if (checked) {
                        { Icon(Icons.Outlined.Check, null, tint = colors.accent,
                            modifier = Modifier.testTag("hud.vehicle.check.${vehicle.id}")) }
                    } else null,
                )
            }
            if (model.vehicles.isNotEmpty()) HorizontalDivider(Modifier.testTag("hud.vehicle.divider"))
            DropdownMenuItem(text = { Text(stringResource(R.string.hud_vehicle_add_menu)) },
                onClick = onAdd, leadingIcon = { Icon(Icons.Outlined.Add, null) },
                modifier = Modifier.testTag("hud.vehicle.add"))
        }
    }
}
