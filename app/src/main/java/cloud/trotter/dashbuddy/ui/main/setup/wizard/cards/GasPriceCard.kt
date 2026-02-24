package cloud.trotter.dashbuddy.ui.main.setup.wizard.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.model.vehicle.FuelType
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.WizardCardHeader
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardStep
import java.util.Locale

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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

        Spacer(modifier = Modifier.height(32.dp))

        if (isAuto) {
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
                    valueRange = 1.0f..8.0f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}