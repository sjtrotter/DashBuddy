package cloud.trotter.dashbuddy.feature.setup.wizard.cards

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.feature.setup.R
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.feature.setup.wizard.components.WizardCardHeader
import cloud.trotter.dashbuddy.feature.setup.wizard.model.WizardStep

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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WizardCardHeader(step)

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.wizard_gas_price_fuel_type_question),
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
                Text(stringResource(R.string.wizard_gas_price_ev_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.wizard_gas_price_ev_desc),
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
                    Text(stringResource(R.string.wizard_gas_price_auto_update_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.wizard_gas_price_auto_update_desc),
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
                    text = Formats.money(price.toDouble()),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.wizard_gas_price_current_regional_average),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                text = Formats.money(price.toDouble()),
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