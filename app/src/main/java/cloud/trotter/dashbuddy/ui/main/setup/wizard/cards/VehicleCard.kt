package cloud.trotter.dashbuddy.ui.main.setup.wizard.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.VehicleDropdown
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.WizardCardHeader
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.VehicleType
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardStep
import java.util.Locale

@Composable
fun VehicleCard(
    step: WizardStep,
    vehicleType: VehicleType,
    year: String,
    make: String,
    model: String,
    trim: String,
    mpg: Float,
    availableYears: List<String>,
    availableMakes: List<String>,
    availableModels: List<String>,
    availableTrims: List<String>,
    onTypeSelected: (VehicleType) -> Unit,
    onYearSelected: (String) -> Unit,
    onMakeSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onTrimSelected: (String) -> Unit,
    onMpgChanged: (Float) -> Unit
) {
    val uriHandler = LocalUriHandler.current

    // Trigger the manual override UI if any of these match
    val isCustom = make == "Not Listed" || model == "Not Listed" || trim == "Not Listed"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WizardCardHeader(step, vehicleType)

        Spacer(modifier = Modifier.height(24.dp))

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

        AnimatedVisibility(
            visible = vehicleType == VehicleType.CAR,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                VehicleDropdown(
                    label = "Year",
                    value = year,
                    options = availableYears,
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

                // Hide Model dropdown if they clicked Not Listed for Make
                AnimatedVisibility(visible = make.isNotBlank() && make != "Not Listed") {
                    Column {
                        VehicleDropdown(
                            label = "Model",
                            value = model,
                            options = availableModels,
                            onValueChanged = onModelSelected,
                            enabled = availableModels.isNotEmpty()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Hide Trim dropdown if they clicked Not Listed for Model
                        AnimatedVisibility(visible = model.isNotBlank() && model != "Not Listed") {
                            VehicleDropdown(
                                label = "Trim / Options",
                                value = trim,
                                options = availableTrims,
                                onValueChanged = onTrimSelected,
                                enabled = availableTrims.isNotEmpty()
                            )
                        }
                    }
                }
            }
        }

        // --- THE ESCAPE HATCH UI ---
        AnimatedVisibility(visible = vehicleType == VehicleType.CAR && isCustom) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedButton(
                    onClick = {
                        val queryParts = listOfNotNull(
                            year.takeIf { it.isNotBlank() },
                            make.takeIf { it != "Not Listed" && it.isNotBlank() },
                            model.takeIf { it != "Not Listed" && it.isNotBlank() }
                        ).joinToString("+")

                        val searchUrl = "https://www.google.com/search?q=$queryParts+mpg"
                        uriHandler.openUri(searchUrl)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Lookup my MPG on Google")
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = String.format(Locale.getDefault(), "%.1f MPG", mpg),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Slider(
                        value = mpg,
                        onValueChange = onMpgChanged,
                        valueRange = 8f..65f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = "Slide to match your vehicle's combined MPG.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
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