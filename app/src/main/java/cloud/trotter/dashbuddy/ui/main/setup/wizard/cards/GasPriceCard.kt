package cloud.trotter.dashbuddy.ui.main.setup.wizard.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.model.vehicle.FuelType
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.WizardCardHeader
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardStep
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GasPriceCard(
    step: WizardStep,
    fuelType: FuelType,
    isAuto: Boolean,
    price: Float,
    isFetching: Boolean,
    onFuelTypeSelected: (FuelType) -> Unit,
    onAutoToggle: (Boolean) -> Unit,
    onPriceChange: (Float) -> Unit
) {
    // Edge case: If they switch to Electricity and the price is still at "Gas" levels,
    // snap it down to a sensible $0.30 default for a better user experience.
    LaunchedEffect(fuelType) {
        if (fuelType == FuelType.ELECTRICITY && price > 1.0f) {
            onPriceChange(0.30f)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WizardCardHeader(step)

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "What type of fuel do you use?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FuelType.entries.forEach { type ->
                FilterChip(
                    selected = fuelType == type,
                    onClick = { onFuelTypeSelected(type) },
                    label = { Text(type.displayName) }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (fuelType == FuelType.ELECTRICITY) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Manual EV Charging Costs", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Because public Superchargers and home charging rates vary wildly, please set your average equivalent 'per-gallon' cost manually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-Update Gas Prices", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "We'll fetch the regional average for your fuel type daily.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isAuto,
                    onCheckedChange = onAutoToggle
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        val displayAuto = isAuto && fuelType != FuelType.ELECTRICITY

        if (displayAuto) {
            if (isFetching) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            } else {
                Text(
                    text = String.format(Locale.getDefault(), "$%.2f", price),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Current Regional Average",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                text = String.format(Locale.getDefault(), "$%.2f", price),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(16.dp)
            ) {
                Slider(
                    value = price,
                    onValueChange = onPriceChange,
                    // UPDATED: Start at 10 cents for EVs, otherwise keep $1 minimum for gas.
                    valueRange = if (fuelType == FuelType.ELECTRICITY) 0.10f..8.0f else 1.0f..8.0f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}