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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.VehicleDropdown
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.WizardCardHeader
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardStep
import cloud.trotter.dashbuddy.ui.main.setup.wizard.VEHICLE_NOT_LISTED
import android.net.Uri

@Composable
fun VehicleCard(
    step: WizardStep,
    vehicleClass: VehicleClass,
    year: String,
    make: String,
    model: String,
    trim: String,
    mpg: Float,
    availableYears: List<String>,
    availableMakes: List<String>,
    availableModels: List<String>,
    availableTrims: List<String>,
    onTypeSelected: (VehicleClass) -> Unit,
    onYearSelected: (String) -> Unit,
    onMakeSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onTrimSelected: (String) -> Unit,
    onMpgChanged: (Float) -> Unit
) {
    val uriHandler = LocalUriHandler.current

    // Trigger the manual override UI if any of these match
    val isCustom = make == VEHICLE_NOT_LISTED || model == VEHICLE_NOT_LISTED || trim == VEHICLE_NOT_LISTED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WizardCardHeader(step, vehicleClass)

        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            FilterChip(
                selected = vehicleClass == VehicleClass.SEDAN,
                onClick = { onTypeSelected(VehicleClass.SEDAN) },
                label = { Text(stringResource(R.string.wizard_vehicle_card_type_car)) }
            )
            Spacer(modifier = Modifier.padding(8.dp))
            FilterChip(
                selected = vehicleClass == VehicleClass.E_BIKE,
                onClick = { onTypeSelected(VehicleClass.E_BIKE) },
                label = { Text(stringResource(R.string.wizard_vehicle_card_type_ebike)) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedVisibility(
            visible = vehicleClass == VehicleClass.SEDAN,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                VehicleDropdown(
                    label = stringResource(R.string.wizard_vehicle_card_year_label),
                    value = year,
                    options = availableYears,
                    onValueChanged = onYearSelected
                )

                Spacer(modifier = Modifier.height(8.dp))

                VehicleDropdown(
                    label = stringResource(R.string.wizard_vehicle_card_make_label),
                    value = make,
                    options = availableMakes,
                    onValueChanged = onMakeSelected,
                    enabled = year.isNotBlank() && availableMakes.isNotEmpty()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Hide Model dropdown if they clicked Not Listed for Make
                AnimatedVisibility(visible = make.isNotBlank() && make != VEHICLE_NOT_LISTED) {
                    Column {
                        VehicleDropdown(
                            label = stringResource(R.string.wizard_vehicle_card_model_label),
                            value = model,
                            options = availableModels,
                            onValueChanged = onModelSelected,
                            enabled = availableModels.isNotEmpty()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Hide Trim dropdown if they clicked Not Listed for Model
                        AnimatedVisibility(visible = model.isNotBlank() && model != VEHICLE_NOT_LISTED) {
                            VehicleDropdown(
                                label = stringResource(R.string.wizard_vehicle_card_trim_label),
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
        AnimatedVisibility(visible = vehicleClass == VehicleClass.SEDAN && isCustom) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedButton(
                    onClick = {
                        val query = listOfNotNull(
                            year.takeIf { it.isNotBlank() },
                            make.takeIf { it != VEHICLE_NOT_LISTED && it.isNotBlank() },
                            model.takeIf { it != VEHICLE_NOT_LISTED && it.isNotBlank() },
                            "mpg",
                        ).joinToString(" ")

                        // Uri.encode (#367): unencoded spaces/specials broke the URL.
                        uriHandler.openUri("https://www.google.com/search?q=${Uri.encode(query)}")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.wizard_vehicle_card_content_desc_search),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.wizard_vehicle_card_lookup_mpg))
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.wizard_vehicle_card_mpg_format, Formats.decimal(mpg.toDouble())),
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
                    text = stringResource(R.string.wizard_vehicle_card_mpg_slider_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        if (vehicleClass == VehicleClass.E_BIKE) {
            Text(
                text = stringResource(R.string.wizard_vehicle_card_ebike_note),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}