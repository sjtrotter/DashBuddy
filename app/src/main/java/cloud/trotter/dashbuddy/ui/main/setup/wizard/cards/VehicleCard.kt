package cloud.trotter.dashbuddy.ui.main.setup.wizard.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.VehicleDropdown
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.WizardCardHeader
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.VehicleType
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardStep

/**
 * Gathers the vehicle details to accurately estimate fuel costs per mile.
 * Supports bypassing the calculation for electric bikes/scooters.
 */
@Composable
fun VehicleCard(
    step: WizardStep,
    vehicleType: VehicleType,
    year: String,
    make: String,
    model: String,
    trim: String,
    availableYears: List<String>,
    availableMakes: List<String>,
    availableModels: List<String>,
    availableTrims: List<String>,
    onTypeSelected: (VehicleType) -> Unit,
    onYearSelected: (String) -> Unit,
    onMakeSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onTrimSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // NOTE: WizardCardHeader will dynamically change its icon based on vehicleType!
        WizardCardHeader(step, vehicleType)

        Spacer(modifier = Modifier.height(24.dp))

        // Vehicle Toggle
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            FilterChip(
                selected = vehicleType == VehicleType.CAR,
                onClick = { onTypeSelected(VehicleType.CAR) },
                label = { Text("🚗 Car") }
            )
            Spacer(modifier = Modifier.padding(8.dp))
            FilterChip(
                selected = vehicleType == VehicleType.E_BIKE,
                onClick = { onTypeSelected(VehicleType.E_BIKE) },
                label = { Text("🚲 E-Bike") }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Car Fields (Hidden if E-Bike)
        AnimatedVisibility(
            visible = vehicleType == VehicleType.CAR,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                VehicleDropdown(
                    label = "Year", value = year, options = availableYears,
                    onValueChanged = onYearSelected
                )

                Spacer(modifier = Modifier.height(8.dp))

                VehicleDropdown(
                    label = "Make",
                    value = make,
                    options = availableMakes,
                    onValueChanged = onMakeSelected,
                    enabled = year.isNotBlank() && availableMakes.isNotEmpty()
                )

                Spacer(modifier = Modifier.height(8.dp))

                VehicleDropdown(
                    label = "Model",
                    value = model,
                    options = availableModels,
                    onValueChanged = onModelSelected,
                    enabled = make.isNotBlank() && availableModels.isNotEmpty()
                )

                Spacer(modifier = Modifier.height(8.dp))

                VehicleDropdown(
                    label = "Trim / Options",
                    value = trim,
                    options = availableTrims,
                    onValueChanged = onTrimSelected,
                    enabled = model.isNotBlank() && availableTrims.isNotEmpty()
                )
            }
        }

        if (vehicleType == VehicleType.E_BIKE) {
            Text(
                text = "Awesome! We will ignore gas expenses and set your cost-per-mile to $0.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}