package cloud.trotter.dashbuddy.ui.components.economy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstantPair
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstants
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.state.Platform

@Immutable
data class TimeConstantsPlatformUiState(
    val platform: Platform,
    val automatic: TimeConstantPair,
    val effective: TimeConstantPair,
    val sampleCount: Int,
)

@Immutable
data class TimeConstantsUiState(
    val paceOverride: Double? = null,
    val overheadOverride: Double? = null,
    val platforms: List<TimeConstantsPlatformUiState> = emptyList(),
)

/** Presentation only: the host supplies resolved values and owns every persistence action. */
@Composable
fun EconomyTimeConstantsSection(
    state: TimeConstantsUiState,
    onOverrideChange: (EconomyField, Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.economy_time_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.economy_time_help), style = MaterialTheme.typography.bodySmall)
        TimeOverrideControl(EconomyField.AVG_MIN_PER_MILE, state.paceOverride, onOverrideChange)
        TimeOverrideControl(EconomyField.BASE_PICKUP_MIN, state.overheadOverride, onOverrideChange)
        state.platforms.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.platform.displayName, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.economy_time_automatic_values,
                    Formats.decimal(row.automatic.minutesPerMile, 2), Formats.decimal(row.automatic.stopOverheadMinutes, 2)))
                Text(stringResource(R.string.economy_time_effective_values,
                    Formats.decimal(row.effective.minutesPerMile, 2), Formats.decimal(row.effective.stopOverheadMinutes, 2)))
                Text(pluralStringResource(R.plurals.economy_time_deliveries, row.sampleCount, row.sampleCount))
                if (row.sampleCount < TimeConstants.MIN_SAMPLES) {
                    Text(stringResource(R.string.economy_time_default_progress, row.sampleCount, TimeConstants.MIN_SAMPLES))
                }
                if (row.sampleCount > TimeConstants.WINDOW_SIZE) {
                    Text(stringResource(R.string.economy_time_window, TimeConstants.WINDOW_SIZE))
                }
            }
        }
    }
}

@Composable
private fun TimeOverrideControl(
    field: EconomyField,
    value: Double?,
    onOverrideChange: (EconomyField, Double?) -> Unit,
) {
    // Editing buffer only; resolved economy and persistence remain outside composition.
    var draft by rememberSaveable(field, value) { mutableStateOf(value?.toString().orEmpty()) }
    val parsed = draft.replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && parsed.isFinite() &&
        if (field == EconomyField.AVG_MIN_PER_MILE) parsed > 0 else parsed >= 0
    Column {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            label = { Text(stringResource(if (field == EconomyField.AVG_MIN_PER_MILE)
                R.string.economy_time_pace else R.string.economy_time_overhead)) },
            placeholder = { Text(stringResource(R.string.economy_time_automatic)) },
            isError = draft.isNotBlank() && !valid,
            supportingText = {
                if (draft.isNotBlank() && !valid) Text(stringResource(if (field == EconomyField.AVG_MIN_PER_MILE)
                    R.string.economy_time_invalid_pace else R.string.economy_time_invalid_overhead))
            },
        )
        Row {
            TextButton(enabled = valid, onClick = { onOverrideChange(field, parsed) }) {
                Text(stringResource(R.string.economy_time_set_override))
            }
            TextButton(onClick = { draft = ""; onOverrideChange(field, null) }) {
                Text(stringResource(R.string.economy_time_use_automatic))
            }
        }
    }
}
