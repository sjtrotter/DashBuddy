package cloud.trotter.dashbuddy.feature.setup.wizard.cards

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.feature.setup.R
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.EconomyFieldGroups
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import cloud.trotter.dashbuddy.feature.setup.wizard.components.VehicleDropdown
import cloud.trotter.dashbuddy.feature.setup.wizard.components.WizardCardHeader
import cloud.trotter.dashbuddy.feature.setup.wizard.model.WizardStep
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicleTypeLabel
import cloud.trotter.dashbuddy.feature.setup.wizard.VEHICLE_NOT_LISTED
import android.net.Uri

@Composable
fun VehicleCard(
    step: WizardStep,
    vehicleClass: VehicleClass,
    profile: VehicleProfile,
    consumption: Double,
    electricityPrice: Double,
    onVehicleTypeSelected: (VehicleType) -> Unit,
    onPowertrainSelected: (Powertrain) -> Unit,
    onConsumptionChanged: (Double) -> Unit,
    onElectricityPriceChanged: (Double) -> Unit,
    vehicleLookupAllowed: Boolean,
    manualMpgEntry: Boolean,
    year: String,
    make: String,
    model: String,
    trim: String,
    mpg: Float,
    availableYears: List<String>,
    availableMakes: List<String>,
    availableModels: List<String>,
    availableTrims: List<String>,
    onAllowVehicleLookup: () -> Unit,
    onManualMpgEntry: () -> Unit,
    onYearSelected: (String) -> Unit,
    onMakeSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onTrimSelected: (String) -> Unit,
    onMpgChanged: (Float) -> Unit
) {
    val uriHandler = LocalUriHandler.current

    // Trigger the manual override UI if any of these match
    val isCustom = make == VEHICLE_NOT_LISTED || model == VEHICLE_NOT_LISTED || trim == VEHICLE_NOT_LISTED
    val fields = EconomyFieldGroups.fieldsFor(profile)
    val showMpgEntry = EconomyField.VEHICLE_MPG in fields && (manualMpgEntry || isCustom || !profile.hasCarIdentity)
    val hasVehicleIdentity = listOf(year, make, model).all { it.isNotBlank() && it != VEHICLE_NOT_LISTED }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WizardCardHeader(step, vehicleClass)

        Spacer(modifier = Modifier.height(24.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VehicleType.entries.forEach { type ->
                FilterChip(selected = profile.vehicleType == type,
                    onClick = { onVehicleTypeSelected(type) }, label = { Text(stringResource(vehicleTypeLabel(type))) })
            }
        }
        if (profile is ScooterMoped) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Powertrain.entries.forEach { power ->
                    FilterChip(selected = profile.powertrain == power, onClick = { onPowertrainSelected(power) },
                        label = { Text(stringResource(if (power == Powertrain.GAS) R.string.vehicle_power_gas else R.string.vehicle_power_electric)) })
                }
            }
        }
        if (EconomyField.KWH_PER_100_MI in fields || EconomyField.WH_PER_MILE in fields) {
            VehicleNumberInput(
                label = stringResource(if (EconomyField.WH_PER_MILE in fields) R.string.vehicle_wh_per_mile else R.string.vehicle_kwh_per_100_mi),
                value = consumption, onChange = onConsumptionChanged,
            )
            VehicleNumberInput(label = stringResource(R.string.vehicle_electricity_price),
                value = electricityPrice, onChange = onElectricityPriceChanged)
        }

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedVisibility(
            visible = profile.hasCarIdentity,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                if (hasVehicleIdentity) {
                    Text(
                        text = listOf(year, make, model, trim)
                            .filter { it.isNotBlank() && it != VEHICLE_NOT_LISTED }
                            .joinToString(" "),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!vehicleLookupAllowed) {
                    Text(
                        text = stringResource(R.string.wizard_vehicle_lookup_prompt),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = onAllowVehicleLookup) {
                        Text(stringResource(R.string.wizard_vehicle_lookup_allow))
                    }
                } else {
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
                if (!showMpgEntry) {
                    TextButton(onClick = onManualMpgEntry) {
                        Text(stringResource(R.string.wizard_vehicle_manual_entry))
                    }
                }
            }
        }

        // --- THE ESCAPE HATCH UI ---
        AnimatedVisibility(visible = showMpgEntry) {
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
                        valueRange = 8f..120f,
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

        if (profile is EBike) {
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

/**
 * Documented duplicate of :app's ui/components/economy/CurrencyInput.kt decimal field.
 * The focus-aware text handling is kept here because :feature:setup cannot depend on :app.
 * Keep both in sync; a shared UI module is the follow-up if this boundary changes.
 */
@Composable
private fun VehicleNumberInput(label: String, value: Double, onChange: (Double) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value.toString().removeSuffix(".0")) }
    LaunchedEffect(value, focused) { if (!focused) text = value.toString().removeSuffix(".0") }
    OutlinedTextField(value = text, onValueChange = { input ->
        text = input
        input.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let(onChange)
    }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
}
