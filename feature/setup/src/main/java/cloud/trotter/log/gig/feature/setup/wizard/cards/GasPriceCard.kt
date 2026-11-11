package cloud.trotter.log.gig.feature.setup.wizard.cards

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.feature.setup.R
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatMonthDay
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import cloud.trotter.log.gig.feature.setup.wizard.components.WizardCardHeader
import cloud.trotter.log.gig.feature.setup.wizard.model.GasPriceSource
import cloud.trotter.log.gig.feature.setup.wizard.model.WizardStep
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GasPriceCard(
    step: WizardStep,
    fuelType: FuelType,
    isAuto: Boolean,
    price: Float,
    isFetching: Boolean,
    source: GasPriceSource,
    lookupFailed: Boolean,
    /** The host's composition clock reading (`rememberNow()`); decides "today" at render time. */
    nowMillis: Long,
    onFuelTypeSelected: (FuelType) -> Unit,
    onAutoToggle: (Boolean) -> Unit,
    onPriceChange: (Float) -> Unit,
    onRetry: () -> Unit,
) {
    val line = GasPriceLine.of(source, lookupFailed, price, nowMillis, ZoneId.systemDefault())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
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
            FuelType.entries.filter { it != FuelType.ELECTRICITY }.forEach { type ->
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
            // One toggle: the whole row, text included, flips the switch and TalkBack reads the label with its state.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = isAuto, role = Role.Switch, onValueChange = onAutoToggle),
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
                    onCheckedChange = null
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
                GasPriceSourceLine(line, onRetry)
            }
        } else {
            Text(
                text = Formats.money(price.toDouble()),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            if (fuelType != FuelType.ELECTRICITY) GasPriceSourceLine(line, onRetry)
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

@Composable
private fun GasPriceSourceLine(line: GasPriceLine, onRetry: () -> Unit) {
    val text = when (line) {
        GasPriceLine.Today -> stringResource(R.string.wizard_gas_price_source_today)
        is GasPriceLine.FetchedOn -> stringResource(R.string.wizard_gas_price_source_on_date, formatMonthDay(line.date))
        is GasPriceLine.FailedUsing -> stringResource(R.string.wizard_gas_price_lookup_failed_using,
            Formats.money(line.price.toDouble()), formatMonthDay(line.date))
        GasPriceLine.FailedNoLookup -> stringResource(R.string.wizard_gas_price_lookup_failed_enter)
        GasPriceLine.Typed -> stringResource(R.string.wizard_gas_price_source_typed)
        GasPriceLine.Saved -> stringResource(R.string.wizard_gas_price_source_saved)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (line.offersRetry) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (line.offersRetry) {
        TextButton(onClick = onRetry) { Text(stringResource(R.string.wizard_gas_price_retry)) }
    }
}
